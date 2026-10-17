package dev.nopox.modlog;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Permission nodes, checked through NeoForge's permission API (LuckPerms for NeoForge plugs into it). On a hybrid
 * server (Arclight) a node set explicitly through Bukkit — e.g. by LuckPerms for Bukkit — takes precedence.
 * Without either, the defaults are the operator levels ModLog always used.
 */
public final class Permissions {
    public static final PermissionNode<Boolean> LOOKUP = node("lookup", Commands.LEVEL_GAMEMASTERS,
            "Look up logs", "Inspector, lookups and the log browser GUI");
    public static final PermissionNode<Boolean> ROLLBACK = node("rollback", Commands.LEVEL_ADMINS,
            "Roll back", "Rollback, restore and undo");
    public static final PermissionNode<Boolean> PURGE = node("purge", Commands.LEVEL_OWNERS,
            "Purge logs", "Permanently delete old log entries");

    private static final Bukkit BUKKIT = Bukkit.find();

    private Permissions() {
    }

    public static void register(IEventBus bus) {
        bus.addListener((PermissionGatherEvent.Nodes e) -> e.addNodes(LOOKUP, ROLLBACK, PURGE));
        if (BUKKIT != null) {
            ModLog.LOGGER.info("ModLog: Bukkit permissions are honoured");
        }
    }

    public static boolean check(ServerPlayer player, PermissionNode<Boolean> node) {
        if (BUKKIT != null) {
            Boolean explicit = BUKKIT.explicit(player.getUUID(), node.getNodeName());
            if (explicit != null) {
                return explicit;
            }
        }
        return PermissionAPI.getPermission(player, node);
    }

    /** For command requirements: players by node, the console and command blocks by operator level. */
    public static boolean check(CommandSourceStack source, PermissionNode<Boolean> node) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return check(player, node);
        }
        return source.hasPermission(defaultLevel(node));
    }

    private static int defaultLevel(PermissionNode<Boolean> node) {
        if (node == PURGE) {
            return Commands.LEVEL_OWNERS;
        }
        return node == ROLLBACK ? Commands.LEVEL_ADMINS : Commands.LEVEL_GAMEMASTERS;
    }

    private static PermissionNode<Boolean> node(String name, int defaultLevel, String readable, String description) {
        PermissionNode<Boolean> node = new PermissionNode<>(ModLog.MOD_ID, name, PermissionTypes.BOOLEAN,
                (player, uuid, context) -> player != null && player.hasPermissions(defaultLevel));
        node.setInformation(Component.literal(readable), Component.literal(description));
        return node;
    }

    /** Bukkit's permission API, reached reflectively so ModLog doesn't depend on it. */
    private record Bukkit(Method getPlayer, Method isPermissionSet, Method hasPermission) {
        static @Nullable Bukkit find() {
            try {
                Class<?> bukkit = Class.forName("org.bukkit.Bukkit", false, Permissions.class.getClassLoader());
                Class<?> permissible = Class.forName("org.bukkit.permissions.Permissible", false, Permissions.class.getClassLoader());
                return new Bukkit(bukkit.getMethod("getPlayer", UUID.class),
                        permissible.getMethod("isPermissionSet", String.class),
                        permissible.getMethod("hasPermission", String.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }

        /** The node's value if it's set explicitly for the player, else null. */
        @Nullable Boolean explicit(UUID player, String node) {
            try {
                Object bukkitPlayer = getPlayer.invoke(null, player);
                if (bukkitPlayer != null && (Boolean) isPermissionSet.invoke(bukkitPlayer, node)) {
                    return (Boolean) hasPermission.invoke(bukkitPlayer, node);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                ModLog.LOGGER.debug("Bukkit permission check failed for {}", node, e);
            }
            return null;
        }
    }
}
