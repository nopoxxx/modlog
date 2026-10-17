package dev.nopox.modlog.util;

/** A user error with a translation key, translated where it's shown. */
public class ModLogException extends IllegalArgumentException {
    private final String key;
    private final Object[] args;

    public ModLogException(String key, Object... args) {
        super(key);
        this.key = key;
        this.args = args;
    }

    public String key() {
        return key;
    }

    public Object[] args() {
        return args;
    }
}
