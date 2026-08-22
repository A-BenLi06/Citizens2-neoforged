package net.citizensnpcs.api.util;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

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
        if (defaultUnits == null) {
            Integer ticks = Ints.tryParse(raw);
            if (ticks != null)
                return Duration.ofMillis(ticks * 50L);
        } else if (NUMBER_MATCHER.matcher(raw).matches()) {
            return Duration.of(Longs.tryParse(raw), toChronoUnit(defaultUnits));
        }
        if (raw.endsWith("t"))
            return Duration.ofMillis(Integer.parseInt(raw.substring(0, raw.length() - 1)) * 50L);
        raw = DAY_MATCHER.matcher(raw).replaceFirst("P$1").replace("min", "m").replace("hr", "h");
        if (raw.charAt(0) != 'P') {
            raw = "PT" + raw;
        }
        return Duration.parse(raw);
    }

    public static Duration parse(String raw) {
        return parse(raw, null);
    }

    public static int toTicks(Duration duration) {
        return (int) (duration.toMillis() / 50);
    }

    public static int toSeconds(Duration duration) {
        return (int) duration.getSeconds();
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

    private static final Pattern DAY_MATCHER = Pattern.compile("(\\d+d)");
    private static final Pattern NUMBER_MATCHER = Pattern.compile("(\\d+)");
}
