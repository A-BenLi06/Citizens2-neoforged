package net.yuuniverse.interactions;

import java.util.Locale;
import java.util.Map;

/** Legacy chat animation settings; the delay is measured in server ticks. */
public record WriteDialogueSettings(boolean enabled, Mode mode, int delay) {
    public enum Mode { CHARACTER, WORD }
    public static final WriteDialogueSettings DEFAULT = new WriteDialogueSettings(false, Mode.WORD, 2);

    public WriteDialogueSettings {
        java.util.Objects.requireNonNull(mode, "mode");
        if (delay < 1) throw new IllegalArgumentException("write_dialogues.delay must be a positive tick count");
    }

    static WriteDialogueSettings read(Object value) {
        if (value == null) return DEFAULT;
        if (!(value instanceof Map<?, ?> values)) throw new IllegalArgumentException("Expected a write_dialogues map");
        Object enabled = values.containsKey("enabled") ? values.get("enabled") : DEFAULT.enabled();
        Object mode = values.containsKey("mode") ? values.get("mode") : DEFAULT.mode().name();
        Object delay = values.containsKey("delay") ? values.get("delay") : DEFAULT.delay();
        if (!(enabled instanceof Boolean flag) || !(mode instanceof String name)
                || !(delay instanceof Number count) || !Double.isFinite(count.doubleValue())
                || count.doubleValue() != count.intValue())
            throw new IllegalArgumentException("Expected boolean enabled, text mode and integer delay for write_dialogues");
        return new WriteDialogueSettings(flag, Mode.valueOf(name.toUpperCase(Locale.ROOT)), count.intValue());
    }
}
