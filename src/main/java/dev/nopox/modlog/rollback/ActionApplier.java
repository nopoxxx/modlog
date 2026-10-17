package dev.nopox.modlog.rollback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.compat.NetworkStorage;
import dev.nopox.modlog.compat.NetworkStorages;
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
    /**
     * Update clients and let neighbours adjust their shape (the other half of a double chest re-pairs, fences
     * reconnect), but no neighbour block updates, so restoring doesn't trigger redstone or physics.
     */
    private static final int FLAGS = Block.UPDATE_CLIENTS;

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
            case CONTAINER_ADD, ITEM_PICKUP -> rollback ? extractItems(level, pos, a) : insertItems(level, pos, a);
            case ITEM_DROP -> Result.skipped("item drops can't be undone");
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
        if (NetworkStorages.DATA_MARKER.equals(a.data())) {
            return viaNetwork(level, pos, a, true);
        }
        IItemHandler handler = handlerFor(level, pos, a);
        ItemStack template = itemOf(level, a);
        if (handler == null && ItemStorages.isMarker(a.data())) {
            return Result.skipped("storage not reachable (owner offline?)");
        }
        if (handler == null) {
            // Logged by an older version without the network marker, or the block has no inventory of its own
            return NetworkStorages.find(level, pos) != null ? viaNetwork(level, pos, a, true)
                    : Result.skipped("no inventory");
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
        if (NetworkStorages.DATA_MARKER.equals(a.data())) {
            return viaNetwork(level, pos, a, false);
        }
        IItemHandler handler = handlerFor(level, pos, a);
        ItemStack template = itemOf(level, a);
        if (handler == null && ItemStorages.isMarker(a.data())) {
            return Result.skipped("storage not reachable (owner offline?)");
        }
        if (handler == null) {
            // Logged by an older version without the network marker, or the block has no inventory of its own
            return NetworkStorages.find(level, pos) != null ? viaNetwork(level, pos, a, false)
                    : Result.skipped("no inventory");
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

    /** The inventory a container action happened in: an item storage (backpack) if marked so, else the block's. */
    private static @Nullable IItemHandler handlerFor(ServerLevel level, BlockPos pos, StoredAction a) {
        return ItemStorages.isMarker(a.data())
                ? ItemStorages.find(level.getServer(), a.data())
                : ContainerContents.itemHandler(level, pos);
    }

    /** For actions done through an AE2/RS terminal: move the items into/out of the network the terminal is on. */
    private static Result viaNetwork(ServerLevel level, BlockPos pos, StoredAction a, boolean insert) {
        NetworkStorage network = NetworkStorages.find(level, pos);
        ItemStack template = itemOf(level, a);
        if (network == null) {
            return Result.skipped("no storage network");
        }
        if (template.isEmpty()) {
            return Result.skipped("unknown item " + a.material());
        }
        long moved = insert ? network.insert(template, a.amount()) : network.extract(template, a.amount());
        return itemResult(a.amount(), (int) moved, insert ? "network full or offline" : "items gone from network");
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
