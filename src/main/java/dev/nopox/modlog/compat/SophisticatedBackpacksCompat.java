package dev.nopox.modlog.compat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Sophisticated Backpacks. A backpack's contents are stored server-side under a UUID kept on the item, which is what
 * the marker records. Only loaded when the mod is installed.
 */
final class SophisticatedBackpacksCompat implements ItemStorages.Provider {
    private static final String PREFIX = "backpack:";

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public @Nullable String identify(AbstractContainerMenu menu) {
        if (menu instanceof BackpackContainer backpack) {
            return backpack.getStorageWrapper().getContentsUuid().map(uuid -> PREFIX + uuid).orElse(null);
        }
        return null;
    }

    /**
     * Looks for the real backpack item (in an online player's inventory or an open backpack menu) rather than building
     * a detached wrapper over the same contents, which could overwrite or be overwritten by the live one.
     */
    @Override
    public @Nullable IItemHandler find(MinecraftServer server, String marker) {
        UUID uuid = uuidOf(marker);
        if (uuid == null) {
            return null;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof BackpackContainer open
                    && open.getStorageWrapper().getContentsUuid().filter(uuid::equals).isPresent()) {
                return inventoryOf(open.getStorageWrapper());
            }
            Inventory inventory = player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (holds(stack, uuid)) {
                    return inventoryOf(BackpackWrapper.fromStack(stack));
                }
            }
        }
        return null;
    }

    /**
     * Upgrade slots followed by the main inventory: upgrades moved in/out through the GUI are logged too, and inserting
     * fills upgrade slots first only with items they accept (upgrades), everything else goes to the main inventory.
     */
    private static IItemHandler inventoryOf(IStorageWrapper wrapper) {
        return new CombinedInvWrapper(wrapper.getUpgradeHandler(), wrapper.getInventoryHandler());
    }

    @Override
    public boolean isHolder(ItemStack stack, String marker) {
        UUID uuid = uuidOf(marker);
        return uuid != null && holds(stack, uuid);
    }

    private static boolean holds(ItemStack stack, UUID uuid) {
        if (!(stack.getItem() instanceof BackpackItem)) {
            return false;
        }
        return BackpackWrapper.fromExistingData(stack).flatMap(w -> (Optional<UUID>) w.getContentsUuid())
                .filter(uuid::equals).isPresent();
    }

    private static @Nullable UUID uuidOf(String marker) {
        try {
            return UUID.fromString(marker.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
