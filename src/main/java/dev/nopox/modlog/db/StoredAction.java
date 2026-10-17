package dev.nopox.modlog.db;

import dev.nopox.modlog.action.ActionType;
import org.jetbrains.annotations.Nullable;

/** A logged action with everything needed to undo or redo it. */
public record StoredAction(
        long id,
        String world,
        int x, int y, int z,
        ActionType type,
        String material,
        @Nullable String data,
        int amount,
        byte @Nullable [] nbt
) {
}
