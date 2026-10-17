package dev.nopox.modlog.compat;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.node.container.NetworkNodeContainer;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.api.support.network.item.NetworkItemContext;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.WirelessGridContainerMenu;
import com.refinedmods.refinedstorage.common.storage.portablegrid.AbstractPortableGridContainerMenu;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import dev.nopox.modlog.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/** Refined Storage 2. Only loaded when RS is installed. */
final class RefinedStorageCompat {
    // The wireless grid's network is only reachable through private fields: menu.grid (a WirelessGrid) -> context
    private static final @Nullable Field MENU_GRID = field(AbstractGridContainerMenu.class, "grid");
    private static final @Nullable Field WIRELESS_GRID_CONTEXT = field("com.refinedmods.refinedstorage.common.grid.WirelessGrid", "context");
    private static boolean warnedLocate;

    private RefinedStorageCompat() {
    }

    static boolean isTerminal(AbstractContainerMenu menu) {
        // Grids, crafting grid, pattern grid, wireless grid; not portable grids, whose storage is their own disk
        return menu instanceof AbstractGridContainerMenu && !(menu instanceof AbstractPortableGridContainerMenu);
    }

    static @Nullable GlobalPos locate(AbstractContainerMenu menu) {
        if (!(menu instanceof WirelessGridContainerMenu) || MENU_GRID == null || WIRELESS_GRID_CONTEXT == null) {
            return null;
        }
        try {
            Object grid = MENU_GRID.get(menu);
            if (grid == null || !WIRELESS_GRID_CONTEXT.getDeclaringClass().isInstance(grid)) {
                return null;
            }
            NetworkItemContext context = (NetworkItemContext) WIRELESS_GRID_CONTEXT.get(grid);
            Network network = context.resolveNetwork().orElse(null);
            if (network == null) {
                return null;
            }
            for (NetworkNodeContainer container : network.getComponent(GraphNetworkComponent.class).getContainers()) {
                if (container instanceof InWorldNetworkNodeContainer inWorld && !inWorld.isRemoved()) {
                    return inWorld.getPosition();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedLocate) {
                warnedLocate = true;
                ModLog.LOGGER.warn("Can't find the network of an RS wireless grid; its transactions won't be logged", e);
            }
        }
        return null;
    }

    private static @Nullable Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ModLog.LOGGER.warn("RS field {}.{} not found; wireless grids won't be logged", owner.getName(), name);
            return null;
        }
    }

    private static @Nullable Field field(String owner, String name) {
        try {
            return field(Class.forName(owner), name);
        } catch (ClassNotFoundException e) {
            ModLog.LOGGER.warn("RS class {} not found; wireless grids won't be logged", owner);
            return null;
        }
    }

    static @Nullable NetworkStorage find(ServerLevel level, BlockPos pos) {
        var capability = RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability();
        NetworkNodeContainerProvider provider = level.getCapability(capability, pos, null);
        for (int i = 0; provider == null && i < Direction.values().length; i++) {
            provider = level.getCapability(capability, pos, Direction.values()[i]);
        }
        if (provider == null) {
            return null;
        }
        for (InWorldNetworkNodeContainer container : provider.getContainers()) {
            Network network = container.getNode().getNetwork();
            if (network == null) {
                continue;
            }
            StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
            return new NetworkStorage() {
                @Override
                public long insert(ItemStack template, long amount) {
                    return storage.insert(ItemResource.ofItemStack(template), amount, Action.EXECUTE, Actor.EMPTY);
                }

                @Override
                public long extract(ItemStack template, long amount) {
                    return storage.extract(ItemResource.ofItemStack(template), amount, Action.EXECUTE, Actor.EMPTY);
                }
            };
        }
        return null;
    }
}
