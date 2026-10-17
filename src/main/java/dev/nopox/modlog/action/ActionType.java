package dev.nopox.modlog.action;

import java.util.HashMap;
import java.util.Map;

/**
 * Kinds of logged actions. {@link #id} is persisted in the database, so existing ids must never change.
 */
public enum ActionType {
    BLOCK_BREAK(0, "broke"),
    BLOCK_PLACE(1, "placed"),
    CONTAINER_REMOVE(2, "removed"),
    CONTAINER_ADD(3, "added"),
    /** Picked up from the ground straight into a storage (backpack magnet/pickup upgrades). Undone like an add. */
    ITEM_PICKUP(4, "picked up");

    private static final Map<Integer, ActionType> BY_ID = new HashMap<>();

    static {
        for (ActionType type : values()) {
            BY_ID.put(type.id, type);
        }
    }

    public final int id;
    public final String verb;

    ActionType(int id, String verb) {
        this.id = id;
        this.verb = verb;
    }

    public static ActionType byId(int id) {
        ActionType type = BY_ID.get(id);
        if (type == null) {
            throw new IllegalArgumentException("Unknown action id " + id);
        }
        return type;
    }
}
