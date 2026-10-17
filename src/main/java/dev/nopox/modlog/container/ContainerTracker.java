package dev.nopox.modlog.container;

import dev.nopox.modlog.ModLog;
import dev.nopox.modlog.ModLogConfig;
import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.action.Actor;
import dev.nopox.modlog.action.LoggedAction;
import dev.nopox.modlog.compat.NetworkStorages;
import dev.nopox.modlog.util.NbtBytes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Logs items moved into and out of block inventories by players.
 * <p>
 * Instead of hooking every mod's GUI, this wraps the handling of each player packet that can move items
 * (vanilla container clicks, every modded payload, right/left clicks on blocks with an inventory) and compares the
 * player's own items (inventory + cursor) before and after. Whatever the player lost went into the container,
 * whatever they gained came out of it — minus anything they threw on the ground. This works the same for a vanilla
 * chest and for an AE2/RS terminal, where the "container" is a whole storage network with no slots in the GUI.
 * <p>
 * Main thread only.
 */
public final class ContainerTracker {
    /**
     * A GUI the player opened on a block.
     *
     * @param network the GUI is a storage network terminal (AE2, RS): items go to the network, not the block
     */
    private record Session(AbstractContainerMenu menu, ServerLevel level, BlockPos pos, boolean network) {
    }

    /** Snapshot taken before a packet is handled. */
    private static final class Capture {
        final ServerPlayer player;
        final ServerLevel level;
        final BlockPos pos;
        final boolean network;
        final Map<ItemKey, Integer> before;
        final Map<ItemKey, Integer> tossed = new HashMap<>();

        Capture(ServerPlayer player, ServerLevel level, BlockPos pos, boolean network) {
            this.player = player;
            this.level = level;
            this.pos = pos;
            this.network = network;
            this.before = playerItems(player);
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** Block the player is right-clicking in the packet being handled; a menu opened meanwhile belongs to it. */
    private static final Map<UUID, BlockPos> USING_ON = new HashMap<>();
    private static @Nullable Capture active;

    private ContainerTracker() {
    }

    public static void register(IEventBus bus) {
        bus.addListener(ContainerTracker::onOpen);
        bus.addListener(ContainerTracker::onClose);
        bus.addListener(EventPriority.LOWEST, ContainerTracker::onToss);
        bus.addListener(ContainerTracker::onLogout);
    }

    // ---- Entry points from mixins (called on network threads, the returned task runs on the main thread) ----

    public static Runnable wrapPacket(Packet<?> packet, PacketListener listener, Runnable task) {
        if (!(listener instanceof ServerGamePacketListenerImpl game)) {
            return task;
        }
        return () -> handleVanilla(game.player, packet, task);
    }

    public static Runnable wrapPayload(ServerGamePacketListenerImpl game, Runnable task) {
        return () -> {
            Session session = currentSession(game.player);
            if (session == null) {
                task.run();
            } else {
                capture(game.player, session.level, session.pos, session.network, () -> {
                    task.run();
                    return null;
                });
            }
        };
    }

    public static <T> Supplier<T> wrapPayload(ServerGamePacketListenerImpl game, Supplier<T> task) {
        return () -> {
            Session session = currentSession(game.player);
            return session == null ? task.get() : capture(game.player, session.level, session.pos, session.network, task);
        };
    }

    // ---- Packet handling ----

    private static void handleVanilla(ServerPlayer player, Packet<?> packet, Runnable task) {
        ServerLevel level = player.serverLevel();
        @Nullable BlockPos target = null;
        boolean network = false;
        @Nullable BlockPos usingOn = null;

        if (packet instanceof ServerboundContainerClickPacket
                || packet instanceof ServerboundContainerButtonClickPacket
                || packet instanceof ServerboundPlaceRecipePacket) {
            Session session = currentSession(player);
            if (session != null) {
                level = session.level;
                target = session.pos;
                network = session.network;
            }
        } else if (packet instanceof ServerboundUseItemOnPacket use) {
            usingOn = use.getHitResult().getBlockPos();
            // Sneaking with an item places it against the block instead of using the block
            boolean placing = player.isSecondaryUseActive() && !player.getItemInHand(use.getHand()).isEmpty();
            if (!placing && hasInventory(level, usingOn)) {
                target = usingOn;
            }
        } else if (packet instanceof ServerboundPlayerActionPacket action
                && action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                && hasInventory(level, action.getPos())) {
            // Left-click extraction: Storage Drawers, Functional Storage, ...
            target = action.getPos();
        }

        if (usingOn != null) {
            USING_ON.put(player.getUUID(), usingOn);
        }
        try {
            if (target == null) {
                task.run();
            } else {
                capture(player, level, target, network, () -> {
                    task.run();
                    return null;
                });
            }
        } finally {
            if (usingOn != null) {
                USING_ON.remove(player.getUUID());
            }
        }
    }

    private static <T> T capture(ServerPlayer player, ServerLevel level, BlockPos pos, boolean network, Supplier<T> task) {
        if (active != null || !ModLogConfig.LOG_CONTAINERS.get()) {
            return task.get();
        }
        Capture capture = new Capture(player, level, pos.immutable(), network);
        active = capture;
        try {
            return task.get();
        } finally {
            active = null;
            finish(capture);
        }
    }

    private static void finish(Capture c) {
        Map<ItemKey, Integer> after = playerItems(c.player);
        // container change = -(player change) - tossed
        Map<ItemKey, Integer> delta = new HashMap<>();
        c.before.forEach((k, n) -> delta.merge(k, n, Integer::sum));
        after.forEach((k, n) -> delta.merge(k, -n, Integer::sum));
        c.tossed.forEach((k, n) -> delta.merge(k, -n, Integer::sum));

        long now = System.currentTimeMillis();
        String world = c.level.dimension().location().toString();
        Actor actor = Actor.of(c.player);
        delta.forEach((key, n) -> {
            if (n == 0) {
                return;
            }
            ItemStack stack = key.stack();
            ModLog.log(new LoggedAction(now, actor, world, c.pos.getX(), c.pos.getY(), c.pos.getZ(),
                    n > 0 ? ActionType.CONTAINER_ADD : ActionType.CONTAINER_REMOVE,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    c.network ? NetworkStorages.DATA_MARKER : null, Math.abs(n),
                    NbtBytes.compress((CompoundTag) stack.save(c.level.registryAccess())), null, 0));
        });
    }

    private static Map<ItemKey, Integer> playerItems(ServerPlayer player) {
        Map<ItemKey, Integer> items = new HashMap<>();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            add(items, inventory.getItem(i));
        }
        add(items, player.containerMenu.getCarried());
        return items;
    }

    private static void add(Map<ItemKey, Integer> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.merge(new ItemKey(stack), stack.getCount(), Integer::sum);
        }
    }

    private static boolean hasInventory(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) != null && ContainerContents.itemHandler(level, pos) != null;
    }

    private static @Nullable Session currentSession(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && session.menu == player.containerMenu ? session : null;
    }

    // ---- Events ----

    private static void onOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AbstractContainerMenu menu = event.getContainer();
        ServerLevel level = player.serverLevel();
        BlockPos pos = USING_ON.get(player.getUUID());
        if (pos == null || level.getBlockEntity(pos) == null) {
            pos = blockEntityBehind(menu);
        }
        // Menus not backed by a block (crafting table, backpacks, wireless terminals) aren't tracked
        if (pos == null || level.getBlockEntity(pos) == null) {
            SESSIONS.remove(player.getUUID());
            return;
        }
        SESSIONS.put(player.getUUID(), new Session(menu, level, pos.immutable(), NetworkStorages.isTerminal(menu)));
    }

    /** Fallback for menus opened without a click: a slot whose container is a block entity (vanilla-style blocks). */
    private static @Nullable BlockPos blockEntityBehind(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof BlockEntity be) {
                return be.getBlockPos();
            }
        }
        return null;
    }

    private static void onClose(PlayerContainerEvent.Close event) {
        Session session = SESSIONS.get(event.getEntity().getUUID());
        if (session != null && session.menu == event.getContainer()) {
            SESSIONS.remove(event.getEntity().getUUID());
        }
    }

    private static void onToss(ItemTossEvent event) {
        Capture c = active;
        if (c != null && c.player == event.getPlayer() && !event.isCanceled()) {
            ItemStack stack = event.getEntity().getItem();
            add(c.tossed, stack);
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }
}
