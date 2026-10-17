package dev.nopox.modlog.compat;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;
import dev.nopox.modlog.util.NbtBytes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Logs items that Sophisticated storage upgrades take from the ground into a storage, bypassing the player's
 * inventory: the magnet upgrade (pulls items in) and the pickup upgrade (diverts what the player picks up).
 * Called from the Sophisticated mixins; main thread only.
 */
public final class BackpackPickups {
    /** The player whose item pickup is being processed, so the pickup upgrade knows who picked up. */
    private static @Nullable Player picking;

    private BackpackPickups() {
    }

    public static void register(IEventBus bus) {
        // Before Sophisticated's own handler diverts the item into a backpack
        bus.addListener(EventPriority.HIGHEST, (ItemEntityPickupEvent.Pre e) -> picking = e.getPlayer());
        bus.addListener(EventPriority.LOWEST, (ItemEntityPickupEvent.Post e) -> picking = null);
        bus.addListener((ServerTickEvent.Post e) -> picking = null);
    }

    /** A magnet upgrade inserted items. @param player the player wearing/holding the storage, null for a placed one */
    public static void magnet(Optional<UUID> storage, @Nullable Player player, Level level, BlockPos storagePos,
                              ItemStack item, int count) {
        log(storage, player, "#magnet", level, storagePos, item, count);
    }

    /** A pickup-type upgrade took items the player was picking up. */
    public static void pickup(Optional<UUID> storage, Level level, ItemStack item, int count) {
        Player player = picking;
        // Not within a player's pickup: nobody to attribute it to, and no position
        if (player != null) {
            log(storage, player, "#pickup", level, player.blockPosition(), item, count);
        }
    }

    private static void log(Optional<UUID> storage, @Nullable Player player, String machine, Level level, BlockPos storagePos,
                            ItemStack item, int count) {
        if (count <= 0 || level.isClientSide() || item.isEmpty() || !ModLogConfig.LOG_CONTAINERS.get()) {
            return;
        }
        // Carried by a player: logged at the player, rolled back through the backpack's UUID.
        // Placed as a block: logged at the block, rolled back through the block's inventory.
        String marker = player != null ? storage.map(uuid -> "backpack:" + uuid).orElse(null) : null;
        BlockPos pos = player != null ? player.blockPosition() : storagePos;
        Level where = player != null ? player.level() : level;
        ItemStack template = item.copyWithCount(1);
        ModLog.log(new LoggedAction(System.currentTimeMillis(), player != null ? Actor.of(player) : Actor.nonPlayer(machine),
                where.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), ActionType.ITEM_PICKUP,
                BuiltInRegistries.ITEM.getKey(template.getItem()).toString(), marker, count,
                NbtBytes.compress((CompoundTag) template.save(where.registryAccess())), null, 0));
    }
}
