package dev.nopox.modlog.compat;

import dev.nopox.modlog.ModLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Storages that live in an item rather than a block (backpacks). Their transactions are logged at the player's
 * position with a marker in the {@code data} column, e.g. {@code backpack:<uuid>}, which identifies the storage
 * for rollback wherever the item has gone since.
 */
public final class ItemStorages {
    interface Provider {
        /** Marker prefix this provider owns, e.g. {@code backpack:}. */
        String prefix();

        /** Marker for the storage the menu shows, or null if the menu isn't one of this provider's. */
        @Nullable String identify(AbstractContainerMenu menu);

        /** The storage's inventory, if it can be found right now. */
        @Nullable IItemHandler find(MinecraftServer server, String marker);

        /** Whether the stack is the item holding the storage (so it isn't counted as a moved item). */
        boolean isHolder(ItemStack stack, String marker);

        /** Short human-readable form of a marker in the given language. */
        String describe(String language, String marker);
    }

    private static final List<Provider> PROVIDERS = new ArrayList<>();

    private ItemStorages() {
    }

    public static void init() {
        PROVIDERS.add(new PlayerInventories());
        PROVIDERS.add(new EntityInventories());
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            PROVIDERS.add(new SophisticatedBackpacksCompat());
            BackpackPickups.register(NeoForge.EVENT_BUS);
            ModLog.LOGGER.info("ModLog: Sophisticated Backpacks integration enabled");
        }
    }

    public static @Nullable String identify(AbstractContainerMenu menu) {
        for (Provider provider : PROVIDERS) {
            String marker = provider.identify(menu);
            if (marker != null) {
                return marker;
            }
        }
        return null;
    }

    /** Whether {@code data} is an item storage marker. */
    public static boolean isMarker(@Nullable String data) {
        return provider(data) != null;
    }

    /** Short human-readable form of a marker, e.g. {@code backpack 1a2b3c4d}. */
    public static String describe(String language, String marker) {
        Provider provider = provider(marker);
        return provider == null ? marker : provider.describe(language, marker);
    }

    /** Marker for a player's own inventory. */
    public static String playerInventory(UUID player) {
        return PlayerInventories.PREFIX + player;
    }

    public static @Nullable IItemHandler find(MinecraftServer server, String marker) {
        Provider provider = provider(marker);
        return provider == null ? null : provider.find(server, marker);
    }

    public static boolean isHolder(ItemStack stack, @Nullable String marker) {
        Provider provider = provider(marker);
        return provider != null && provider.isHolder(stack, marker);
    }

    private static @Nullable Provider provider(@Nullable String data) {
        if (data != null) {
            for (Provider provider : PROVIDERS) {
                if (data.startsWith(provider.prefix())) {
                    return provider;
                }
            }
        }
        return null;
    }
}
