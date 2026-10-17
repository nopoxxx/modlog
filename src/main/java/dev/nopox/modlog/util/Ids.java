package dev.nopox.modlog.util;

public final class Ids {
    private Ids() {
    }

    /** Drops the {@code minecraft:} namespace for display: {@code minecraft:stone} → {@code stone}, {@code ae2:...} unchanged. */
    public static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
