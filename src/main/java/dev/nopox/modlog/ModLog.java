package dev.nopox.modlog;

import com.mojang.logging.LogUtils;
import dev.nopox.modlog.action.LoggedAction;
import dev.nopox.modlog.command.ModLogCommand;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.compat.NetworkStorages;
import dev.nopox.modlog.container.ContainerTracker;
import dev.nopox.modlog.db.Storage;
import dev.nopox.modlog.inspect.Inspector;
import dev.nopox.modlog.listener.BlockListener;
import dev.nopox.modlog.listener.ItemListener;
import dev.nopox.modlog.listener.TntTracker;
import dev.nopox.modlog.rollback.RollbackManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;

@Mod(ModLog.MOD_ID)
public final class ModLog {
    public static final String MOD_ID = "modlog";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable Storage storage;

    public ModLog(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, ModLogConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(ModLog::onServerStarting);
        NeoForge.EVENT_BUS.addListener(ModLog::onServerStopped);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> ModLogCommand.register(e.getDispatcher()));
        BlockListener.register(NeoForge.EVENT_BUS);
        ItemListener.register(NeoForge.EVENT_BUS);
        TntTracker.register(NeoForge.EVENT_BUS);
        ContainerTracker.register(NeoForge.EVENT_BUS);
        RollbackManager.register(NeoForge.EVENT_BUS);
        NetworkStorages.init();
        ItemStorages.init();
        Inspector.register(NeoForge.EVENT_BUS);
    }

    /** Storage for the running server, or null while no server is running (or the database failed to open). */
    public static @Nullable Storage storage() {
        return storage;
    }

    public static void log(LoggedAction action) {
        Storage s = storage;
        if (s != null) {
            s.log(action);
        }
    }

    private static void onServerStarting(ServerStartingEvent event) {
        Path file = event.getServer().getServerDirectory().resolve(ModLogConfig.SQLITE_FILE.get());
        try {
            storage = Storage.open(file, ModLogConfig.FLUSH_INTERVAL_MS.get());
            LOGGER.info("ModLog database opened at {}", file.toAbsolutePath());
        } catch (SQLException e) {
            LOGGER.error("Failed to open ModLog database at {}; logging is DISABLED", file.toAbsolutePath(), e);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        Storage s = storage;
        storage = null;
        if (s != null) {
            s.close();
            LOGGER.info("ModLog database closed");
        }
    }
}
