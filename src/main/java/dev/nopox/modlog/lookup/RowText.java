package dev.nopox.modlog.lookup;

import dev.nopox.modlog.action.ActionType;
import dev.nopox.modlog.compat.ItemStorages;
import dev.nopox.modlog.compat.NetworkStorages;
import dev.nopox.modlog.db.LookupRow;
import dev.nopox.modlog.util.Ids;
import dev.nopox.modlog.util.Lang;
import org.jetbrains.annotations.Nullable;

/** How a logged action reads in chat and in the GUI: "<actor> <verb> <subject>". */
public final class RowText {
    private static final int MAX_SUBJECT = 60;

    private RowText() {
    }

    /** What the action was done to: {@code 5 x iron_ingot}, {@code zombie "Bob"}, the chat message, ... */
    public static String subject(LookupRow row) {
        return switch (row.type()) {
            case CHAT, COMMAND -> truncate(row.data() == null ? "" : row.data());
            case ENTITY_KILL -> Ids.shortId(row.material()) + (row.data() == null ? "" : " \"" + row.data() + "\"");
            case SIGN_EDIT -> Ids.shortId(row.material());
            default -> (row.amount() != 1 ? row.amount() + " x " : "") + Ids.shortId(row.material());
        };
    }

    /** Where container items went, for the line itself: {@code → item_frame}, {@code → network}, or null. */
    public static @Nullable String where(String language, LookupRow row) {
        if (NetworkStorages.DATA_MARKER.equals(row.data())) {
            return Lang.tr(language, "where.network");
        }
        if (ItemStorages.isMarker(row.data()) && row.type() != ActionType.ITEM_PICKUP) {
            return ItemStorages.describe(language, row.data());
        }
        return null;
    }

    /** Extra detail for tooltips, or null. */
    public static @Nullable String details(String language, LookupRow row) {
        return switch (row.type()) {
            case SIGN_EDIT -> Lang.tr(language, "detail.sign_text", row.data() == null ? "" : row.data());
            case CHAT, COMMAND -> row.data() != null && row.data().length() > MAX_SUBJECT ? row.data() : null;
            default -> null;
        };
    }

    private static String truncate(String text) {
        return text.length() <= MAX_SUBJECT ? text : text.substring(0, MAX_SUBJECT - 3) + "...";
    }
}
