package dev.nopox.modlog.db;

import dev.nopox.modlog.action.ActionType;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lookup filters. Empty sets and null values mean "no filter".
 *
 * @param world     dimension to search in; required when {@code center} is set
 * @param center    position for {@code radius}; with radius 0 this is an exact-position lookup
 * @param sinceTime only actions at or after this epoch millis
 */
public record LookupQuery(
        Set<String> actors,
        Set<ActionType> actions,
        Set<String> materials,
        @Nullable String world,
        @Nullable BlockPos center,
        int radius,
        @Nullable Long sinceTime
) {
    public static LookupQuery at(String world, BlockPos pos, Set<ActionType> actions) {
        return new LookupQuery(Set.of(), actions, Set.of(), world, pos, 0, null);
    }

    String toSqlWhere(List<Object> params) {
        StringBuilder sql = new StringBuilder("WHERE 1 = 1");
        if (world != null) {
            sql.append(" AND w.name = ?");
            params.add(world);
        }
        if (center != null) {
            sql.append(" AND a.x BETWEEN ? AND ? AND a.z BETWEEN ? AND ? AND a.y BETWEEN ? AND ?");
            params.add(center.getX() - radius);
            params.add(center.getX() + radius);
            params.add(center.getZ() - radius);
            params.add(center.getZ() + radius);
            params.add(center.getY() - radius);
            params.add(center.getY() + radius);
        }
        if (sinceTime != null) {
            sql.append(" AND a.time >= ?");
            params.add(sinceTime);
        }
        appendIn(sql, params, "ac.name", actors);
        appendIn(sql, params, "a.action", actions.stream().map(t -> t.id).collect(Collectors.toSet()));
        appendIn(sql, params, "m.name", materials);
        return sql.toString();
    }

    private static void appendIn(StringBuilder sql, List<Object> params, String column, Set<?> values) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (")
                .append(String.join(", ", java.util.Collections.nCopies(values.size(), "?")))
                .append(')');
        params.addAll(values);
    }
}
