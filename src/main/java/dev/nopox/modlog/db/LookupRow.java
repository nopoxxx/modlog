package dev.nopox.modlog.db;

import dev.nopox.modlog.action.ActionType;
import org.jetbrains.annotations.Nullable;

public record LookupRow(
        long id,
        long time,
        String actor,
        String world,
        int x, int y, int z,
        ActionType type,
        String material,
        @Nullable String data,
        int amount,
        @Nullable String contents,
        int contentsCount,
        boolean rolledBack
) {
}
