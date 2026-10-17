package dev.nopox.modlog.compat;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.support.network.InWorldNetworkNodeContainer;
import com.refinedmods.refinedstorage.common.api.support.network.NetworkNodeContainerProvider;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Refined Storage 2. Only loaded when RS is installed. */
final class RefinedStorageCompat {
    private RefinedStorageCompat() {
    }

    static boolean isTerminal(AbstractContainerMenu menu) {
        // Grid, crafting grid, pattern grid
        return menu instanceof AbstractGridContainerMenu;
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
