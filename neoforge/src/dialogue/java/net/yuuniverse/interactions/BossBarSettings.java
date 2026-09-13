package net.yuuniverse.interactions;

import java.util.Locale;
import java.util.Map;
import net.minecraft.world.BossEvent;

/** The legacy boss_bar section, including Bukkit's style names. */
public record BossBarSettings(boolean enabled, BossEvent.BossBarColor color, BossEvent.BossBarOverlay overlay,
        boolean changeProgressWithTime) {
    public static final BossBarSettings DEFAULT = new BossBarSettings(true, BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.NOTCHED_10, false);

    public BossBarSettings {
        java.util.Objects.requireNonNull(color, "color");
        java.util.Objects.requireNonNull(overlay, "overlay");
    }

    static BossBarSettings read(Object value) {
        if (value == null) return DEFAULT;
        if (!(value instanceof Map<?, ?> values)) throw new IllegalArgumentException("Expected a boss_bar map");
        String style = text(values, "style", "SEGMENTED_10").toUpperCase(Locale.ROOT);
        if (style.equals("SOLID")) style = "PROGRESS";
        else if (style.startsWith("SEGMENTED_")) style = "NOTCHED_" + style.substring("SEGMENTED_".length());
        return new BossBarSettings(flag(values, "enabled", DEFAULT.enabled()),
                BossEvent.BossBarColor.valueOf(text(values, "color", DEFAULT.color().name()).toUpperCase(Locale.ROOT)),
                BossEvent.BossBarOverlay.valueOf(style),
                flag(values, "change_progress_with_time", DEFAULT.changeProgressWithTime()));
    }

    private static String text(Map<?, ?> values, String key, String fallback) {
        if (!values.containsKey(key)) return fallback;
        if (values.get(key) instanceof String value) return value;
        throw new IllegalArgumentException("Expected text for boss_bar." + key);
    }

    private static boolean flag(Map<?, ?> values, String key, boolean fallback) {
        if (!values.containsKey(key)) return fallback;
        if (values.get(key) instanceof Boolean value) return value;
        throw new IllegalArgumentException("Expected a boolean for boss_bar." + key);
    }
}
