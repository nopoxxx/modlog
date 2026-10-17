package dev.nopox.modlog.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TimeFormat {
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)([wdhms])");

    private TimeFormat() {
    }

    /** Compact "time ago" text, e.g. {@code 45s ago}, {@code 3.2m ago}, {@code 1.5d ago}. */
    public static String ago(long millis) {
        double seconds = Math.max(0, millis) / 1000.0;
        if (seconds < 60) {
            return "%ds ago".formatted((long) seconds);
        }
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%.1fm ago", seconds / 60);
        }
        if (seconds < 86400) {
            return String.format(Locale.ROOT, "%.1fh ago", seconds / 3600);
        }
        return String.format(Locale.ROOT, "%.1fd ago", seconds / 86400);
    }

    /**
     * Parses durations like {@code 30m}, {@code 1h30m}, {@code 1.5d} or {@code 2w}.
     *
     * @return millis
     * @throws IllegalArgumentException if the text isn't a duration
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
            throw new IllegalArgumentException("Invalid time '" + text + "', use e.g. 30m, 1h30m, 2d");
        }
        return (long) millis;
    }
}
