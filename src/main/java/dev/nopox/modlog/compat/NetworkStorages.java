package dev.nopox.modlog.compat;

import dev.nopox.modlog.ModLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Storage networks of optional mods. Each integration class is only loaded when its mod is present, so the
 * mod's classes are never touched otherwise.
 */
public final class NetworkStorages {
    /** Value of the {@code data} column for container actions that went to a storage network through a terminal. */
    public static final String DATA_MARKER = "network";

    private static final List<BiFunction<ServerLevel, BlockPos, NetworkStorage>> FINDERS = new ArrayList<>();
    private static final List<Predicate<AbstractContainerMenu>> TERMINALS = new ArrayList<>();
    private static final List<Function<AbstractContainerMenu, GlobalPos>> LOCATORS = new ArrayList<>();

    private NetworkStorages() {
    }

    public static void init() {
        if (ModList.get().isLoaded("ae2")) {
            FINDERS.add(Ae2Compat::find);
            TERMINALS.add(Ae2Compat::isTerminal);
            LOCATORS.add(Ae2Compat::locate);
            ModLog.LOGGER.info("ModLog: AE2 integration enabled");
        }
        if (ModList.get().isLoaded("refinedstorage")) {
            FINDERS.add(RefinedStorageCompat::find);
            TERMINALS.add(RefinedStorageCompat::isTerminal);
            LOCATORS.add(RefinedStorageCompat::locate);
            ModLog.LOGGER.info("ModLog: Refined Storage integration enabled");
        }
    }

    /** The storage network the block at {@code pos} is part of, if any. */
    public static @Nullable NetworkStorage find(ServerLevel level, BlockPos pos) {
        for (var finder : FINDERS) {
            NetworkStorage storage = finder.apply(level, pos);
            if (storage != null) {
                return storage;
            }
        }
        return null;
    }

    /**
     * For a terminal not tied to a block (AE2 wireless terminal, RS wireless grid): the position of some block on the
     * network it's linked to, so its transactions can be logged there and rolled back through that network.
     */
    public static @Nullable GlobalPos locate(AbstractContainerMenu menu) {
        for (var locator : LOCATORS) {
            GlobalPos pos = locator.apply(menu);
            if (pos != null) {
                return pos;
            }
        }
        return null;
    }

    /** Whether the menu is a view onto a whole storage network rather than the block's own slots. */
    public static boolean isTerminal(AbstractContainerMenu menu) {
        for (var terminal : TERMINALS) {
            if (terminal.test(menu)) {
                return true;
            }
        }
        return false;
    }
}
