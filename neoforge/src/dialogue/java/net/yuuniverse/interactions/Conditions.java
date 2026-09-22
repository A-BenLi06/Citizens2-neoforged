package net.yuuniverse.interactions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerPlayer;

/** Original string/number requirements: ANDed entries, OR alternatives and complete operand expansion. */
public final class Conditions {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");
    // Original ConditionalType declaration order determines interpretation when several operators occur.
    private static final List<String> OPERATORS = List.of("==", "!=", "equals", "!equals", "equalsIgnoreCase",
            "!equalsIgnoreCase", "startsWith", "!startsWith", "contains", "!contains", ">", ">=", "<", "<=");

    private Conditions() { }

    public static boolean all(List<String> requires, ServerPlayer player) {
        return all(requires, player, null);
    }

    public static boolean all(List<String> requires, ServerPlayer player, ProgressStore progress) {
        if (requires == null || requires.isEmpty()) return true;
        for (String require : requires) {
            if (!holds(require, value -> expandOperand(value, token -> resolveToken(token, player, progress))))
                return false;
        }
        return true;
    }

    /** The resolver returns null for an incomplete operand; unresolved values never satisfy negation. */
    static boolean holds(String raw, Function<String, String> resolver) {
        if (raw == null) return false;
        int start = 0;
        while (true) {
            int end = findOutsideToken(raw, " or ", start);
            if (branch(raw.substring(start, end < 0 ? raw.length() : end), resolver)) return true;
            if (end < 0) return false;
            start = end + 4;
        }
    }

    private static boolean branch(String expression, Function<String, String> resolver) {
        for (String operator : OPERATORS) {
            String delimiter = " " + operator + " ";
            int split = findOutsideToken(expression, delimiter, 0);
            if (split < 0) continue;
            // Original evaluation expands the complete left operand before the complete right operand.
            String left = resolver.apply(expression.substring(0, split));
            String right = resolver.apply(expression.substring(split + delimiter.length()));
            if (left != null && right != null && compare(left, operator, right)) return true;
        }
        return false;
    }

    private static boolean compare(String left, String operator, String right) {
        return switch (operator) {
            case "==", "equals" -> left.equals(right);
            case "!=", "!equals" -> !left.equals(right);
            case "equalsIgnoreCase" -> left.equalsIgnoreCase(right);
            case "!equalsIgnoreCase" -> !left.equalsIgnoreCase(right);
            case "startsWith" -> left.toLowerCase(Locale.ROOT).startsWith(right.toLowerCase(Locale.ROOT));
            case "!startsWith" -> !left.toLowerCase(Locale.ROOT).startsWith(right.toLowerCase(Locale.ROOT));
            case "contains" -> left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            case "!contains" -> !left.toLowerCase(Locale.ROOT).contains(right.toLowerCase(Locale.ROOT));
            default -> numeric(left, operator, right);
        };
    }

    private static boolean numeric(String left, String operator, String right) {
        try {
            // Like the original numeric parser, ignore surrounding numeric whitespace, not string whitespace.
            int order = new BigDecimal(left.trim()).compareTo(new BigDecimal(right.trim()));
            return switch (operator) {
                case ">" -> order > 0;
                case ">=" -> order >= 0;
                case "<" -> order < 0;
                case "<=" -> order <= 0;
                default -> false;
            };
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    /** Grammar is scanned before expansion; operators inside a placeholder argument are ordinary data. */
    private static int findOutsideToken(String raw, String delimiter, int start) {
        for (int i = start; i < raw.length(); i++) {
            if (raw.charAt(i) == '%') {
                int end = tokenEnd(raw, i, '%');
                if (end >= 0) { i = end; continue; }
            }
            if (raw.startsWith(delimiter, i)) return i;
        }
        return -1;
    }

    private static int tokenEnd(String raw, int start, char closing) {
        int end = raw.indexOf(closing, start + 1);
        return end > start + 1 && raw.charAt(start + 1) != ' ' && raw.charAt(end - 1) != ' ' ? end : -1;
    }

    /** Single passes over original percent tokens and their brace arguments; returned values are never reparsed. */
    static String expandOperand(String raw, Function<String, String> resolver) {
        return expand(raw, '%', '%', token -> {
            String inner = expand(token.substring(1, token.length() - 1), '{', '}',
                    argument -> resolver.apply("%" + argument.substring(1, argument.length() - 1) + "%"));
            return inner == null ? null : resolver.apply("%" + inner + "%");
        });
    }

    private static String expand(String raw, char opening, char closing, Function<String, String> resolver) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == opening) {
                int end = tokenEnd(raw, i, closing);
                if (end >= 0) {
                    String value = resolver.apply(raw.substring(i, end + 1));
                    if (value == null) return null;
                    result.append(value);
                    i = end;
                    continue;
                }
            }
            result.append(raw.charAt(i));
        }
        return result.toString();
    }

    private static String resolveToken(String token, ServerPlayer player, ProgressStore progress) {
        if (player == null) return null;
        String inner = token.substring(1, token.length() - 1);
        try {
            if (inner.startsWith("interactions_") && progress != null && !progress.isReadable(player.getUUID()))
                return null;
            if (inner.startsWith("checkitem_")) {
                // Unsupported/malformed predicates must not become a successful "== no".
                var query = CheckItem.Query.parse(inner);
                if (query == null) return null;
                return CheckItem.holds(query, player) ? "yes" : "no";
            }
            String expanded = Text.placeholders(token, player, progress);
            return expanded.equals(token) ? null : expanded;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOGGER.warn("Could not resolve dialogue condition {}: {}", token, failure.toString());
            return null;
        }
    }
}
