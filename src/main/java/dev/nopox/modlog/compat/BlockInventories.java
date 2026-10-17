package dev.nopox.modlog.compat;

import dev.nopox.modlog.ModLog;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Direct access to a block's whole inventory for mods whose item capability is limited by side configuration
 * (Mekanism machines don't let automation take from inputs or fill energy slots). Logging and rollback act like a
 * player in the GUI, so they need what the GUI sees.
 */
public final class BlockInventories {
    private static final List<Function<BlockEntity, IItemHandler>> PROVIDERS = new ArrayList<>();

    private BlockInventories() {
    }

    public static void init() {
        if (ModList.get().isLoaded("mekanism")) {
            PROVIDERS.add(MekanismCompat::inventory);
            ModLog.LOGGER.info("ModLog: Mekanism integration enabled");
        }
    }

    /** The block entity's full inventory, or null to use its item capability. */
    public static @Nullable IItemHandler of(@Nullable BlockEntity be) {
        if (be != null) {
            for (var provider : PROVIDERS) {
                IItemHandler handler = provider.apply(be);
                if (handler != null) {
                    return handler;
                }
            }
        }
        return null;
    }
}
