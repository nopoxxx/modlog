package dev.nopox.modlog.listener;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.util.NbtBytes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Logs items players drop and pick up. Pickups are logged into the player's own inventory, so rolling one back takes
 * the items from the player again; drops are for lookups only.
 */
public final class ItemListener {
    private ItemListener() {
    }

    public static void register(IEventBus bus) {
        // LOWEST so drops cancelled by other mods aren't logged
        bus.addListener(EventPriority.LOWEST, ItemListener::onToss);
        bus.addListener(ItemListener::onPickup);
    }

    private static void onToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        if (!ModLogConfig.LOG_ITEM_DROPS.get() || player.level().isClientSide() || player instanceof FakePlayer) {
            return;
        }
        ItemEntity entity = event.getEntity();
        log(player, entity, ActionType.ITEM_DROP, entity.getItem(), entity.getItem().getCount(), null);
    }

    private static void onPickup(ItemEntityPickupEvent.Post event) {
        Player player = event.getPlayer();
        if (!ModLogConfig.LOG_ITEM_PICKUPS.get() || player.level().isClientSide() || player instanceof FakePlayer) {
            return;
        }
        ItemStack original = event.getOriginalStack();
        int picked = original.getCount() - event.getCurrentStack().getCount();
        if (picked > 0) {
            log(player, event.getItemEntity(), ActionType.ITEM_PICKUP, original, picked,
                    ItemStorages.playerInventory(player.getUUID()));
        }
    }

    private static void log(Player player, ItemEntity entity, ActionType type, ItemStack stack, int count,
                            @Nullable String marker) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack template = stack.copyWithCount(1);
        BlockPos pos = entity.blockPosition();
        ModLog.log(new LoggedAction(System.currentTimeMillis(), Actor.of(player),
                player.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), type,
                BuiltInRegistries.ITEM.getKey(template.getItem()).toString(), marker, count,
                NbtBytes.compress((CompoundTag) template.save(player.level().registryAccess())), null, 0));
    }
}
