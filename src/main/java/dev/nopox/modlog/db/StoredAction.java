package dev.nopox.modlog.db;

import dev.nopox.modlog.action.ActionType;
import org.jetbrains.annotations.Nullable;

/**
 * A logged action with everything needed to undo or redo it.
 *
 * @param actorKey who did it: a player's UUID, or a {@code #}-prefixed name (see {@link dev.nopox.modlog.action.Actor})
 */
public record StoredAction(
        long id,
        String actorKey,
        String world,
        int x, int y, int z,
        ActionType type,
        String material,
        @Nullable String data,
        int amount,
        byte @Nullable [] nbt,
        byte @Nullable [] drops
) {
}
