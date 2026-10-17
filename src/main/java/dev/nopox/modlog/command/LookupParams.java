package dev.nopox.modlog.command;

import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.db.LookupQuery;
import dev.nopox.modlog.util.TimeFormat;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Parses CoreProtect-style lookup parameters:
 * <pre>
 *   u:Steve,Alex      actors (players, or #creeper, #tnt, ...)
 *   t:2h30m           time window
 *   r:10              radius around the executor; r:#global (default) or r:#world for the whole dimension
 *   a:-block          actions: block, +block, -block, container, +container, -container
 *   b:stone,ae2:sky_stone_block   blocks / items (minecraft: is implied)
 * </pre>
 */
public final class LookupParams {
    public static final String USAGE = "u:<users> t:<time> r:<radius|#world|#global> a:<action> b:<blocks>";

    private LookupParams() {
    }

    /** @throws IllegalArgumentException with a user-facing message */
    public static LookupQuery parse(String input, CommandSourceStack source) {
        Set<String> actors = new HashSet<>();
        Set<ActionType> actions = EnumSet.noneOf(ActionType.class);
        Set<String> materials = new HashSet<>();
        @Nullable Long since = null;
        @Nullable String world = null;
        @Nullable BlockPos center = null;
        int radius = 0;

        for (String token : input.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0 || colon == token.length() - 1) {
                throw new IllegalArgumentException("Invalid parameter '" + token + "'. Usage: " + USAGE);
            }
            String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1);
            switch (key) {
                case "u", "user", "users" -> actors.addAll(split(value));
                case "t", "time" -> since = System.currentTimeMillis() - TimeFormat.parseDuration(value);
                case "r", "radius" -> {
                    switch (value.toLowerCase(Locale.ROOT)) {
                        case "#global", "global" -> {
                            world = null;
                            center = null;
                        }
                        case "#world", "world" -> {
                            world = currentWorld(source);
                            center = null;
                        }
                        default -> {
                            try {
                                radius = Integer.parseInt(value);
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("Invalid radius '" + value + "'");
                            }
                            if (radius < 0) {
                                throw new IllegalArgumentException("Radius can't be negative");
                            }
                            if (source.getEntity() == null) {
                                throw new IllegalArgumentException("r:<number> needs a position; use r:#global from console");
                            }
                            world = currentWorld(source);
                            center = BlockPos.containing(source.getPosition());
                        }
                    }
                }
                case "a", "action" -> {
                    for (String a : split(value)) {
                        actions.addAll(parseAction(a));
                    }
                }
                case "b", "block", "blocks", "i", "item", "items" -> {
                    for (String m : split(value)) {
                        materials.add(m.contains(":") ? m : "minecraft:" + m);
                    }
                }
                default -> throw new IllegalArgumentException("Unknown parameter '" + key + ":'. Usage: " + USAGE);
            }
        }
        return new LookupQuery(actors, actions, materials, world, center, radius, since);
    }

    private static Set<ActionType> parseAction(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "block" -> EnumSet.of(ActionType.BLOCK_BREAK, ActionType.BLOCK_PLACE);
            case "+block", "place" -> EnumSet.of(ActionType.BLOCK_PLACE);
            case "-block", "break" -> EnumSet.of(ActionType.BLOCK_BREAK);
            case "container" -> EnumSet.of(ActionType.CONTAINER_ADD, ActionType.CONTAINER_REMOVE);
            case "+container" -> EnumSet.of(ActionType.CONTAINER_ADD);
            case "-container" -> EnumSet.of(ActionType.CONTAINER_REMOVE);
            default -> throw new IllegalArgumentException(
                    "Unknown action '" + action + "'. Use block, +block, -block, container, +container, -container");
        };
    }

    private static String currentWorld(CommandSourceStack source) {
        return source.getLevel().dimension().location().toString();
    }

    private static Set<String> split(String value) {
        Set<String> parts = new HashSet<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return parts;
    }
}
