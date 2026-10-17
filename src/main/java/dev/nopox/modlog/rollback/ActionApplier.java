package dev.nopox.modlog.rollback;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.compat.NetworkStorage;
import dev.nopox.modlog.compat.NetworkStorages;
import dev.nopox.modlog.container.ContainerContents;
import dev.nopox.modlog.db.StoredAction;
import dev.nopox.modlog.util.Drops;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
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

    /** @param noRoom skipped because the target had no room for the items (it exists, though) */
    record Result(Outcome outcome, int blocks, int items, @Nullable String skipReason, boolean noRoom) {
        Result(Outcome outcome, int blocks, int items, @Nullable String skipReason) {
            this(outcome, blocks, items, skipReason, false);
        }

        static Result skipped(String reason) {
            return new Result(Outcome.SKIPPED, 0, 0, reason);
        }
    }

    private ActionApplier() {
    }

    /** @param reclaimer takes back what rolled-back actions gave to players; null to not reclaim */
    static Result apply(MinecraftServer server, StoredAction a, boolean rollback, @Nullable Reclaimer reclaimer) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(a.world())));
        if (level == null) {
            return Result.skipped("skip.world_not_loaded");
        }
        BlockPos pos = new BlockPos(a.x(), a.y(), a.z());
        Result result = switch (a.type()) {
            case BLOCK_BREAK -> rollback ? placeBlock(level, pos, a) : removeBlock(level, pos, a);
            case BLOCK_PLACE -> rollback ? removeBlock(level, pos, a) : placeBlock(level, pos, a);
            case CONTAINER_ADD, ITEM_PICKUP -> rollback ? extractItems(level, pos, a) : insertItems(level, pos, a);
            case ITEM_DROP -> Result.skipped("skip.drop");
            case CHAT, COMMAND -> Result.skipped("skip.chat");
            case ENTITY_KILL -> rollback ? respawnEntity(level, a) : removeEntity(level, a);
            case SIGN_EDIT -> setSignText(level, pos, a, rollback ? "before" : "after");
            case CONTAINER_REMOVE -> rollback ? insertItems(level, pos, a) : extractItems(level, pos, a);
        };
        return reclaimer != null && rollback ? reclaim(reclaimer, level, pos, a, result) : result;
    }

    private static Result reclaim(Reclaimer reclaimer, ServerLevel level, BlockPos pos, StoredAction a, Result result) {
        if (result.outcome() == Outcome.SKIPPED && !(a.type() == ActionType.CONTAINER_REMOVE && result.noRoom())) {
            return result;
        }
        switch (a.type()) {
            // Undoing a deposit or pickup removed items from the world: they can stand in for items to take back later
            case CONTAINER_ADD, ITEM_PICKUP -> reclaimer.credit(itemOf(level, a), result.items());
            // Items go back into the container: take them all from whoever took them out, even those that no longer
            // fit (a machine slot automation can't fill); those are settled when the rollback ends
            case CONTAINER_REMOVE -> {
                ItemStack template = itemOf(level, a);
                int inserted = result.outcome() == Outcome.SKIPPED ? 0 : result.items();
                reclaimer.reclaim(template, a.amount(), a.actorKey(), level, pos, null, Reclaimer.Order.TAKER_FIRST);
                if (inserted < a.amount()) {
                    reclaimer.overflow(level, pos, a.data(), template, a.amount() - inserted);
                    return new Result(Outcome.DONE, 0, a.amount(), null);
                }
            }
            // A player's placed block is gone: the item it was placed from is out of the world again
            case BLOCK_PLACE -> {
                if (result.blocks() > 0 && !a.actorKey().startsWith("#")) {
                    ResourceLocation id = ResourceLocation.tryParse(a.material());
                    if (id != null) {
                        reclaimer.creditPlaced(BuiltInRegistries.BLOCK.get(id).asItem());
                    }
                }
            }
            // The block or entity is back: take what fell out of it
            case BLOCK_BREAK, ENTITY_KILL -> {
                if (result.blocks() > 0) {
                    for (Drops.Drop drop : Drops.decode(a.drops(), level.registryAccess()).drops()) {
                        reclaimer.reclaim(drop.stack(), drop.stack().getCount(), a.actorKey(), level, pos, drop.entity(),
                                Reclaimer.Order.GROUND_FIRST);
                    }
                }
            }
            default -> {
            }
        }
        return result;
    }

    /** Fluids need neighbour updates, or flowing fluid around a removed source never drains. */
    private static int flagsFor(BlockState state) {
        return state.getBlock() instanceof LiquidBlock ? Block.UPDATE_ALL : FLAGS;
    }

    /** Brings a killed entity back from its logged data, alive and well. */
    private static Result respawnEntity(ServerLevel level, StoredAction a) {
        if (a.nbt() == null) {
            return Result.skipped("skip.no_entity_data");
        }
        CompoundTag data = NbtBytes.decompress(a.nbt());
        if (data.hasUUID("UUID") && level.getEntity(data.getUUID("UUID")) != null) {
            return new Result(Outcome.DONE, 0, 0, null);
        }
        data.putShort("Fire", (short) -1);
        data.remove("DeathTime");
        data.remove("HurtTime");
        Entity entity = EntityType.loadEntityRecursive(data, level, e -> e);
        if (entity == null) {
            return Result.skipped("skip.unknown_entity");
        }
        if (entity instanceof LivingEntity living) {
            living.setHealth(living.getMaxHealth());
            living.deathTime = 0;
        }
        return level.tryAddFreshEntityWithPassengers(entity)
                ? new Result(Outcome.DONE, 1, 0, null)
                : Result.skipped("skip.cant_spawn");
    }

    /** Restore of a kill: removes the entity that a rollback brought back. */
    private static Result removeEntity(ServerLevel level, StoredAction a) {
        if (a.nbt() == null) {
            return Result.skipped("skip.no_entity_data");
        }
        CompoundTag data = NbtBytes.decompress(a.nbt());
        Entity entity = data.hasUUID("UUID") ? level.getEntity(data.getUUID("UUID")) : null;
        if (entity == null) {
            return new Result(Outcome.DONE, 0, 0, null);
        }
        entity.discard();
        return new Result(Outcome.DONE, 1, 0, null);
    }

    /** Puts the sign text from before or after the edit back. */
    private static Result setSignText(ServerLevel level, BlockPos pos, StoredAction a, String which) {
        if (a.nbt() == null || !(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) {
            return Result.skipped("skip.sign_gone");
        }
        CompoundTag text = NbtBytes.decompress(a.nbt()).getCompound(which);
        sign.loadWithComponents(text, level.registryAccess());
        sign.setChanged();
        level.sendBlockUpdated(pos, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_CLIENTS);
        return new Result(Outcome.DONE, 1, 0, null);
    }

    /**
     * Puts items into the inventory a container action happened in.
     *
     * @return how many went in, or -1 if that inventory doesn't exist (anymore)
     */
    static int insertInto(ServerLevel level, BlockPos pos, @Nullable String data, ItemStack template, int count) {
        if (NetworkStorages.DATA_MARKER.equals(data)) {
            NetworkStorage network = NetworkStorages.find(level, pos);
            return network == null ? -1 : (int) network.insert(template, count);
        }
        IItemHandler handler = ItemStorages.isMarker(data)
                ? ItemStorages.find(level.getServer(), data)
                : ContainerContents.itemHandler(level, pos);
        if (handler == null) {
            return -1;
        }
        int remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(remaining, template.getMaxStackSize());
            ItemStack left = ItemHandlerHelper.insertItemStacked(handler, template.copyWithCount(chunk), false);
            remaining -= chunk - left.getCount();
            if (!left.isEmpty()) {
                break;
            }
        }
        return count - remaining;
    }

    /** The logged block state of a block action, or null if the block no longer exists. */
    static @Nullable BlockState stateOf(StoredAction a) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                    a.material() + (a.data() == null ? "" : a.data()), false).blockState();
        } catch (CommandSyntaxException e) {
            return null;
        }
    }

    /**
     * What a block action turns {@code current} into when applied, by the same rules as applying it (never replaces a
     * block it didn't log), or null if it would be skipped. For previews.
     */
    static @Nullable BlockState simulate(StoredAction a, boolean rollback, BlockState current) {
        boolean place = (a.type() == ActionType.BLOCK_BREAK) == rollback;
        if (a.type() != ActionType.BLOCK_BREAK && a.type() != ActionType.BLOCK_PLACE) {
            return null;
        }
        if (place) {
            BlockState state = stateOf(a);
            return state != null && current.canBeReplaced() ? state : null;
        }
        return BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString().equals(a.material())
                ? Blocks.AIR.defaultBlockState() : null;
    }

    private static Result placeBlock(ServerLevel level, BlockPos pos, StoredAction a) {
        BlockState state = stateOf(a);
        if (state == null) {
            return Result.skipped("skip.unknown_block");
        }
        BlockState current = level.getBlockState(pos);
        if (current == state) {
            return new Result(Outcome.DONE, 0, 0, null);
        }
        if (!current.canBeReplaced()) {
            return Result.skipped("skip.occupied");
        }
        level.removeBlockEntity(pos);
        level.setBlock(pos, state, flagsFor(state));

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
            return Result.skipped("skip.changed");
        }
        // Remove the block entity first so the block doesn't spill its contents on removal
        level.removeBlockEntity(pos);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flagsFor(current));
        return new Result(Outcome.DONE, 1, 0, null);
    }

    private static Result insertItems(ServerLevel level, BlockPos pos, StoredAction a) {
        if (NetworkStorages.DATA_MARKER.equals(a.data())) {
            return viaNetwork(level, pos, a, true);
        }
        IItemHandler handler = handlerFor(level, pos, a);
        ItemStack template = itemOf(level, a);
        if (handler == null && ItemStorages.isMarker(a.data())) {
            return Result.skipped("skip.storage_unreachable");
        }
        if (handler == null) {
            // Logged by an older version without the network marker, or the block has no inventory of its own
            return NetworkStorages.find(level, pos) != null ? viaNetwork(level, pos, a, true)
                    : Result.skipped("skip.no_inventory");
        }
        if (template.isEmpty()) {
            return Result.skipped("skip.unknown_item");
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
        return insertResult(a.amount(), a.amount() - remaining, "skip.inventory_full");
    }

    private static Result extractItems(ServerLevel level, BlockPos pos, StoredAction a) {
        if (NetworkStorages.DATA_MARKER.equals(a.data())) {
            return viaNetwork(level, pos, a, false);
        }
        IItemHandler handler = handlerFor(level, pos, a);
        ItemStack template = itemOf(level, a);
        if (handler == null && ItemStorages.isMarker(a.data())) {
            return Result.skipped("skip.storage_unreachable");
        }
        if (handler == null) {
            // Logged by an older version without the network marker, or the block has no inventory of its own
            return NetworkStorages.find(level, pos) != null ? viaNetwork(level, pos, a, false)
                    : Result.skipped("skip.no_inventory");
        }
        if (template.isEmpty()) {
            return Result.skipped("skip.unknown_item");
        }
        int remaining = a.amount();
        for (int slot = 0; slot < handler.getSlots() && remaining > 0; slot++) {
            if (ItemStack.isSameItemSameComponents(handler.getStackInSlot(slot), template)) {
                remaining -= handler.extractItem(slot, remaining, false).getCount();
            }
        }
        return itemResult(a.amount(), a.amount() - remaining, "skip.items_gone");
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
            return Result.skipped("skip.no_network");
        }
        if (template.isEmpty()) {
            return Result.skipped("skip.unknown_item");
        }
        long moved = insert ? network.insert(template, a.amount()) : network.extract(template, a.amount());
        return insert ? insertResult(a.amount(), (int) moved, "skip.network_full")
                : itemResult(a.amount(), (int) moved, "skip.items_gone_network");
    }

    private static Result insertResult(int wanted, int moved, String reason) {
        return moved == 0 ? new Result(Outcome.SKIPPED, 0, 0, reason, true) : itemResult(wanted, moved, reason);
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
