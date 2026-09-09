package net.yuuniverse.interactions;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.server.level.ServerPlayer;

/**
 * Evaluates a {@code requires} entry.
 * <p>
 * Every one in the migrated data has the shape {@code %checkitem_lorecontains:<lore>,amt:N% == yes} - a placeholder
 * compared against a literal. That is the whole condition language those files use, so it is the whole language here.
 * Anything unrecognised is reported and treated as "does not hold", which keeps a mistyped condition from handing out
 * goods for free rather than from blocking a purchase; of the two failure directions that is the safe one.
 */
public final class Conditions {
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");

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
        int eq = expression.indexOf("==");
        int ne = expression.indexOf("!=");
        boolean negated = ne >= 0 && (eq < 0 || ne < eq);
        int split = negated ? ne : eq;
        if (split < 0) {
            LOGGER.warn("Dialogue condition has no comparison, so it cannot hold: {}", raw);
            return false;
        }
        String left = expression.substring(0, split).trim();
        String right = expression.substring(split + 2).trim().toLowerCase(Locale.ROOT);
        String actual = resolver.apply(left);
        if (actual == null) {
            LOGGER.warn("Dialogue condition uses a placeholder this server cannot answer: {}", left);
            return false;
        }
        return negated != actual.equalsIgnoreCase(right);
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
        if (inner.equals("player") || inner.equals("player_name"))
            return player.getGameProfile().getName();
        return null;
    }
}
