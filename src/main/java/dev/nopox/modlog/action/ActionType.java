package dev.nopox.modlog.action;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Kinds of logged actions. {@link #id} is persisted in the database, so existing ids must never change.
 */
public enum ActionType {
    BLOCK_BREAK(0),
    BLOCK_PLACE(1),
    CONTAINER_REMOVE(2),
    CONTAINER_ADD(3),
    /** Picked up from the ground straight into a storage (backpack magnet/pickup upgrades). Undone like an add. */
    ITEM_PICKUP(4),
    /** A player dropped items on the ground. Logged for lookups only, it can't be undone. */
    ITEM_DROP(5),
    /** An entity killed or destroyed: mobs, armor stands, item frames, vehicles. Undone by bringing it back. */
    ENTITY_KILL(6),
    /** Sign text changed. {@code nbt} holds the text before and after. */
    SIGN_EDIT(7),
    /** Chat message, lookups only. The message is in {@code data}. */
    CHAT(8),
    /** Command, lookups only. The command line is in {@code data}. */
    COMMAND(9);

    /** Logged for lookups only; rollback can't undo these. */
    public boolean lookupOnly() {
        return this == ITEM_DROP || this == CHAT || this == COMMAND;
    }

    private static final Map<Integer, ActionType> BY_ID = new HashMap<>();

    static {
        for (ActionType type : values()) {
            BY_ID.put(type.id, type);
        }
    }

    public final int id;

    ActionType(int id) {
        this.id = id;
    }

    /** Translation key of the verb, e.g. {@code action.block_break} ("broke"). */
    public String verbKey() {
        return "action." + name().toLowerCase(Locale.ROOT);
    }

    public static ActionType byId(int id) {
        ActionType type = BY_ID.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown action id " + id);
        }
        return type;
    }
}
