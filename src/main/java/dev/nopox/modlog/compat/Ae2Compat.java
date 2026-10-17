package dev.nopox.modlog.compat;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.implementations.menuobjects.ItemMenuHost;
import appeng.helpers.WirelessTerminalMenuHost;
import appeng.menu.me.common.MEStorageMenu;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Applied Energistics 2. Only loaded when AE2 is installed. */
final class Ae2Compat {
    private Ae2Compat() {
    }

    static boolean isTerminal(AbstractContainerMenu menu) {
        // Terminals, ME chest and wireless terminals; not portable cells, whose storage is the cell in the item
        return menu instanceof MEStorageMenu storage
                && (!(storage.getHost() instanceof ItemMenuHost<?>) || storage.getHost() instanceof WirelessTerminalMenuHost<?>);
    }

    static @Nullable GlobalPos locate(AbstractContainerMenu menu) {
        if (!(menu instanceof MEStorageMenu storage) || !(storage.getHost() instanceof WirelessTerminalMenuHost<?> host)) {
            return null;
        }
        // The node of the access point the terminal is connected through
        IGridNode node = host.getActionableNode();
        if (node == null || node.getGrid() == null) {
            return null;
        }
        for (IGridNode candidate : node.getGrid().getNodes()) {
            if (candidate.getOwner() instanceof BlockEntity be && be.getLevel() != null && !be.isRemoved()) {
                return GlobalPos.of(be.getLevel().dimension(), be.getBlockPos());
            }
        }
        return null;
    }

    static @Nullable NetworkStorage find(ServerLevel level, BlockPos pos) {
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host == null) {
            return null;
        }
        // Cable buses expose their parts per side; any node of the bus is on the same grid
        IGridNode node = host.getGridNode(null);
        for (int i = 0; node == null && i < Direction.values().length; i++) {
            node = host.getGridNode(Direction.values()[i]);
        }
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null) {
            return null;
        }
        MEStorage storage = grid.getStorageService().getInventory();
        return new NetworkStorage() {
            @Override
            public long insert(ItemStack template, long amount) {
                AEItemKey key = AEItemKey.of(template);
                return key == null ? 0 : storage.insert(key, amount, Actionable.MODULATE, IActionSource.empty());
            }

            @Override
            public long extract(ItemStack template, long amount) {
                AEItemKey key = AEItemKey.of(template);
                return key == null ? 0 : storage.extract(key, amount, Actionable.MODULATE, IActionSource.empty());
            }
        };
    }
}
