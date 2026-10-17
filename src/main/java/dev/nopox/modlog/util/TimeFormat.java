package dev.nopox.modlog.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeFormat {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)([wdhms])");

    private TimeFormat() {
    }

    /** Compact "time ago" text in the given language, e.g. {@code 45s ago}, {@code 3.2m ago}, {@code 1.5d ago}. */
    public static String ago(String language, long millis) {
        double seconds = Math.max(0, millis) / 1000.0;
        if (seconds < 60) {
            return Lang.tr(language, "time.ago.seconds", (long) seconds);
        }
        if (seconds < 3600) {
            return Lang.tr(language, "time.ago.minutes", seconds / 60);
        }
        if (seconds < 86400) {
            return Lang.tr(language, "time.ago.hours", seconds / 3600);
        }
        return Lang.tr(language, "time.ago.days", seconds / 86400);
    }

    /** Compact duration, e.g. {@code 10m}, {@code 2h30m}, {@code 1d}. */
    public static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        StringBuilder out = new StringBuilder();
        long[] units = {604_800, 86_400, 3_600, 60, 1};
        String[] names = {"w", "d", "h", "m", "s"};
        for (int i = 0; i < units.length; i++) {
            if (seconds >= units[i]) {
                out.append(seconds / units[i]).append(names[i]);
                seconds %= units[i];
            }
        }
        return out.isEmpty() ? "0s" : out.toString();
    }

    /**
     * Parses durations like {@code 30m}, {@code 1h30m}, {@code 1.5d} or {@code 2w}.
     *
     * @return millis
     * @throws ModLogException if the text isn't a duration
     */
    public static long parseDuration(String text) {
        Matcher m = DURATION_PART.matcher(text.toLowerCase(Locale.ROOT));
        double millis = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) {
                break;
            }
            double value = Double.parseDouble(m.group(1));
            millis += value * switch (m.group(2)) {
                case "w" -> 604_800_000L;
                case "d" -> 86_400_000L;
                case "h" -> 3_600_000L;
                case "m" -> 60_000L;
                default -> 1_000L;
            };
            end = m.end();
        }
        if (end == 0 || end != text.length()) {
            throw new ModLogException("error.invalid_time", text);
        }
        return (long) millis;
    }
}
