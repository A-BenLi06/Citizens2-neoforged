package net.yuuniverse.interactions;

import java.util.Map;
import net.minecraft.world.phys.Vec3;

/** Per-conversation hologram_dialogues settings. */
public record HologramSettings(boolean enabled, double offsetY, double offsetHorizontal) {
    public static final HologramSettings DEFAULT = new HologramSettings(false, 2.7, 0);
    static final double LINE_HEIGHT = 0.2;

    public HologramSettings {
        if (!Double.isFinite(offsetY) || !Double.isFinite(offsetHorizontal))
            throw new IllegalArgumentException("Hologram offsets must be finite");
    }

    static HologramSettings read(Object value) {
        if (value == null) return DEFAULT;
        if (!(value instanceof Map<?, ?> values)) throw new IllegalArgumentException("Expected a hologram_dialogues map");
        Object enabled = values.containsKey("enabled") ? values.get("enabled") : DEFAULT.enabled();
        if (!(enabled instanceof Boolean flag)) throw new IllegalArgumentException("Expected hologram_dialogues.enabled to be boolean");
        return new HologramSettings(flag, number(values, "offset_y", DEFAULT.offsetY()),
                number(values, "offset_horizontal", DEFAULT.offsetHorizontal()));
    }

    private static double number(Map<?, ?> values, String key, double fallback) {
        if (!values.containsKey(key)) return fallback;
        if (values.get(key) instanceof Number value) return value.doubleValue();
        throw new IllegalArgumentException("Expected a number for hologram_dialogues." + key);
    }

    Vec3 top(Vec3 anchor, Vec3 viewer, float yaw, int lines) {
        double x = 0, z = 0;
        if (offsetHorizontal != 0) {
            Vec3 direction = anchor.subtract(viewer);
            if (direction.lengthSqr() < 1E-12) direction = Vec3.directionFromRotation(0, yaw);
            double angle = Math.acos(Math.clamp(direction.x / direction.length(), -1, 1));
            if (direction.z >= 0) angle = -angle;
            double rotated = Math.PI / 2 - angle;
            x = offsetHorizontal * Math.cos(rotated);
            z = offsetHorizontal * Math.sin(rotated);
        }
        return anchor.add(x, offsetY + Math.max(0, lines - 1) * LINE_HEIGHT, z);
    }
}
