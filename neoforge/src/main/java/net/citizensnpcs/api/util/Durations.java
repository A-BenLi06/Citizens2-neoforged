package net.citizensnpcs.api.util;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Parses the duration strings Citizens accepts in its config and commands — {@code "3s"}, {@code "10m"}, {@code "1d"},
 * {@code "40t"}, or a bare tick count.
 * <p>
 * Lifted from upstream's {@code SpigotUtil}, which mixes duration parsing in with Bukkit-specific helpers. Nothing here
 * touches Bukkit or Minecraft, so it lives on its own rather than inside {@code EntityUtil}.
 */
public class Durations {
    private Durations() {
    }

    /**
     * @param raw
     *            the duration string
     * @param defaultUnits
     *            unit to read a bare number as; null means ticks
     */
    public static Duration parse(String raw, TimeUnit defaultUnits) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Duration must not be blank");
        raw = raw.trim();
        if (NUMBER_MATCHER.matcher(raw).matches()) {
            return defaultUnits == null ? Duration.ofMillis(Integer.parseInt(raw) * 50L)
                    : Duration.of(Long.parseLong(raw), toChronoUnit(defaultUnits));
        }
        raw = raw.replace("min", "m").replace("hr", "h");
        int prefix = raw.charAt(0) == '+' || raw.charAt(0) == '-' ? 1 : 0;
        if (prefix < raw.length() && (raw.charAt(prefix) == 'P' || raw.charAt(prefix) == 'p'))
            return Duration.parse(raw);
        if (raw.endsWith("t"))
            return Duration.ofMillis(Integer.parseInt(raw.substring(0, raw.length() - 1)) * 50L);
        return Duration.parse((DAY_MATCHER.matcher(raw).find() ? "P" : "PT") + raw);
    }

    public static Duration parse(String raw) {
        return parse(raw, null);
    }

    public static int toTicks(Duration duration) {
        return Math.toIntExact(duration.toMillis() / 50);
    }

    public static int toSeconds(Duration duration) {
        return Math.toIntExact(duration.getSeconds());
    }

    private static ChronoUnit toChronoUnit(TimeUnit unit) {
        switch (unit) {
            case NANOSECONDS:
                return ChronoUnit.NANOS;
            case MICROSECONDS:
                return ChronoUnit.MICROS;
            case MILLISECONDS:
                return ChronoUnit.MILLIS;
            case SECONDS:
                return ChronoUnit.SECONDS;
            case MINUTES:
                return ChronoUnit.MINUTES;
            case HOURS:
                return ChronoUnit.HOURS;
            case DAYS:
                return ChronoUnit.DAYS;
            default:
                throw new AssertionError(unit);
        }
    }

    private static final Pattern DAY_MATCHER = Pattern.compile("^[+-]?\\d+d", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_MATCHER = Pattern.compile("[+-]?\\d+");
}
