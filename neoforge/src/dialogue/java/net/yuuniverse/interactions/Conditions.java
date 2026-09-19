package net.yuuniverse.interactions;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerPlayer;

/**
 * Evaluates a {@code requires} entry.
 * <p>
 * Supports equality checks and numeric comparisons, including saved dialogue and influence placeholders.
 * Anything unrecognised is reported and treated as "does not hold", which keeps a mistyped condition from handing out
 * goods for free rather than from blocking a purchase; of the two failure directions that is the safe one.
 */
public final class Conditions {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    private static final java.util.regex.Pattern COMPARISON = java.util.regex.Pattern.compile("(==|!=|>=|<=|>|<)");

    private Conditions() {
    }

    public static boolean all(List<String> requires, ServerPlayer player) {
        return all(requires, player, null);
    }

    public static boolean all(List<String> requires, ServerPlayer player, ProgressStore progress) {
        if (requires == null || requires.isEmpty())
            return true;
        for (String require : requires) {
            if (!holds(require, value -> resolve(value, player, progress)))
                return false;
        }
        return true;
    }

    static boolean holds(String raw, Function<String, String> resolver) {
        if (raw == null)
            return true;
        String expression = raw.trim();
        var comparison = COMPARISON.matcher(expression);
        boolean found = false;
        while (comparison.find()) {
            // Comparators inside a placeholder (for example an item's lore) are part of its argument.
            if (expression.substring(0, comparison.start()).chars().filter(value -> value == '%').count() % 2 == 0) {
                found = true;
                break;
            }
        }
        if (!found) {
            LOGGER.warn("Dialogue condition has no comparison, so it cannot hold: {}", raw);
            return false;
        }
        String left = expression.substring(0, comparison.start()).trim();
        String right = resolver.apply(expression.substring(comparison.end()).trim());
        String actual = resolver.apply(left);
        if (actual == null || right == null) {
            LOGGER.warn("Dialogue condition uses a placeholder this server cannot answer: {}", left);
            return false;
        }
        String operator = comparison.group();
        if (operator.equals("==") || operator.equals("!="))
            return operator.equals("!=") != actual.equalsIgnoreCase(right);
        try {
            int order = new java.math.BigDecimal(actual).compareTo(new java.math.BigDecimal(right));
            return switch (operator) {
                case ">" -> order > 0;
                case ">=" -> order >= 0;
                case "<" -> order < 0;
                case "<=" -> order <= 0;
                default -> false;
            };
        } catch (NumberFormatException failure) {
            LOGGER.warn("Dialogue condition requires two finite numbers: {}", raw);
            return false;
        }
    }

    /**
     * @return the placeholder's value, or null when this server has no answer for it
     */
    private static String resolve(String placeholder, ServerPlayer player, ProgressStore progress) {
        String body = placeholder.trim();
        if (!body.startsWith("%") || !body.endsWith("%"))
            return body;
        String inner = body.substring(1, body.length() - 1);
        if (inner.startsWith("interactions_has_dialogue_")) {
            return player == null || progress == null ? null
                    : Boolean.toString(progress.hasSeen(player.getUUID(),
                            inner.substring("interactions_has_dialogue_".length())));
        }
        if (inner.startsWith("checkitem_")) {
            // asked in its non-removing form on purpose: testing a condition must never consume anything, only the
            // remove_item action may. The migrated files do use the removing spelling inside requires.
            return CheckItem.holds(inner.replace("checkitem_remove_", "checkitem_"), player) ? "yes" : "no";
        }
        // A single native placeholder is resolvable; partially expanding an unknown/provider expression is not.
        if (player == null || body.indexOf('%', 1) != body.length() - 1) return null;
        try {
            String expanded = Text.placeholders(body, player, progress);
            return expanded.equals(body) ? null : expanded;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOGGER.warn("Could not resolve dialogue condition {}: {}", body, failure.toString());
            return null;
        }
    }
}
