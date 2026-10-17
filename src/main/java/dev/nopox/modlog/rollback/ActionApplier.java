package dev.nopox.modlog.rollback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.container.ContainerContents;
import dev.nopox.modlog.db.StoredAction;
import dev.nopox.modlog.util.NbtBytes;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

/**
 * Applies one logged action backwards (rollback) or forwards again (restore).
 * <p>
 * Never overwrites something it didn't log: a block is only placed into an empty/replaceable spot and only
 * removed if it's still the logged block.
 */
final class ActionApplier {
    /** Update clients, but no neighbour updates or shape changes: the world is put back exactly as logged. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    enum Outcome {
        /** Changed the world, or it was already in the target state. */
        DONE,
        /** Only some of the items could be moved. */
        PARTIAL,
        SKIPPED
    }

    record Result(Outcome outcome, int blocks, int items, @Nullable String skipReason) {
        static Result skipped(String reason) {
            return new Result(Outcome.SKIPPED, 0, 0, reason);
        }
    }

    private ActionApplier() {
    }

    static Result apply(MinecraftServer server, StoredAction a, boolean rollback) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(a.world())));
        if (level == null) {
            return Result.skipped("world not loaded");
        }
        BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
        return switch (a.type()) {
            case BLOCK_BREAK -> rollback ? placeBlock(level, pos, a) : removeBlock(level, pos, a);
            case BLOCK_PLACE -> rollback ? removeBlock(level, pos, a) : placeBlock(level, pos, a);
            case CONTAINER_ADD -> rollback ? extractItems(level, pos, a) : insertItems(level, pos, a);
            case CONTAINER_REMOVE -> rollback ? insertItems(level, pos, a) : extractItems(level, pos, a);
        };
    }

    private static Result placeBlock(ServerLevel level, BlockPos pos, StoredAction a) {
        BlockState state;
        try {
            state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                    a.material() + (a.data() == null ? "" : a.data()), false).blockState();
        } catch (CommandSyntaxException e) {
            return Result.skipped("unknown block " + a.material());
        }
        BlockState current = level.getBlockState(pos);
        if (current == state) {
            return new Result(Outcome.DONE, 0, 0, null);
        }
        if (!current.canBeReplaced()) {
            return Result.skipped("occupied");
        }
        level.removeBlockEntity(pos);
        level.setBlock(pos, state, FLAGS);

        if (a.nbt() != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                try {
                    be.loadWithComponents(NbtBytes.decompress(a.nbt()), level.registryAccess());
                    be.setChanged();
                    level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
                } catch (RuntimeException e) {
                    ModLog.LOGGER.warn("Failed to restore block entity data at {} ({})", pos, a.material(), e);
                }
            }
        }
        return new Result(Outcome.DONE, 1, 0, null);
    }

    private static Result removeBlock(ServerLevel level, BlockPos pos, StoredAction a) {
        BlockState current = level.getBlockState(pos);
        if (current.isAir()) {
            return new Result(Outcome.DONE, 0, 0, null);
        }
        if (!BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString().equals(a.material())) {
            return Result.skipped("changed since");
        }
        // Remove the block entity first so the block doesn't spill its contents on removal
        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
        return new Result(Outcome.DONE, 1, 0, null);
    }

    private static Result insertItems(ServerLevel level, BlockPos pos, StoredAction a) {
        IItemHandler handler = ContainerContents.itemHandler(level, pos);
        ItemStack template = itemOf(level, a);
        if (handler == null) {
            return Result.skipped("no inventory");
        }
        if (template.isEmpty()) {
            return Result.skipped("unknown item " + a.material());
        }
        int remaining = a.amount();
        while (remaining > 0) {
            int chunk = Math.min(remaining, template.getMaxStackSize());
            ItemStack left = ItemHandlerHelper.insertItemStacked(handler, template.copyWithCount(chunk), false);
            remaining -= chunk - left.getCount();
            if (!left.isEmpty()) {
                break;
            }
        }
        return itemResult(a.amount(), a.amount() - remaining, "inventory full");
    }

    private static Result extractItems(ServerLevel level, BlockPos pos, StoredAction a) {
        IItemHandler handler = ContainerContents.itemHandler(level, pos);
        ItemStack template = itemOf(level, a);
        if (handler == null) {
            return Result.skipped("no inventory");
        }
        if (template.isEmpty()) {
            return Result.skipped("unknown item " + a.material());
        }
        int remaining = a.amount();
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            if (ItemStack.isSameItemSameComponents(handler.getStackInSlot(slot), template)) {
                remaining -= handler.extractItem(slot, remaining, false).getCount();
            }
        }
        return itemResult(a.amount(), a.amount() - remaining, "items gone");
    }

    private static Result itemResult(int wanted, int moved, String reason) {
        if (moved == 0) {
            return Result.skipped(reason);
        }
        return new Result(moved == wanted ? Outcome.DONE : Outcome.PARTIAL, 0, moved, null);
    }

    private static ItemStack itemOf(Level level, StoredAction a) {
        if (a.nbt() == null) {
            return ItemStack.EMPTY;
        }
        return ItemStack.parseOptional(level.registryAccess(), NbtBytes.decompress(a.nbt()));
    }
}
