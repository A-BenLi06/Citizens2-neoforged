package net.yuuniverse.interactions;

import java.util.List;

/** Strict action parameters, shared by preflight and execution. */
final class ActionArguments {
    private ActionArguments() { }

    static List<String> fields(String body, int minimum, int maximum) {
        List<String> fields = Text.semicolons(body);
        if (fields.size() < minimum || fields.size() > maximum)
            throw new IllegalArgumentException("Expected " + minimum + ".." + maximum + " action fields: " + body);
        return fields;
    }

    static int integer(String raw, int minimum, int maximum) {
        int value = Integer.parseInt(raw.trim());
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException("Action integer outside " + minimum + ".." + maximum + ": " + raw);
        return value;
    }

    static double decimal(String raw) {
        double value = Double.parseDouble(raw.trim());
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite action number: " + raw);
        return value;
    }

    static float floating(String raw) {
        float value = Float.parseFloat(raw.trim());
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite action number: " + raw);
        return value;
    }

    static boolean bool(String raw) {
        String value = raw.trim();
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Invalid action boolean: " + raw);
        return Boolean.parseBoolean(value);
    }
}
