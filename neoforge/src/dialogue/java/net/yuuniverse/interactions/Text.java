package net.yuuniverse.interactions;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * The text side of the old plugin: {@code &}-codes and the handful of placeholders its files use.
 * <p>
 * The conversation files are full of {@code &7}, {@code &6&l} and so on, because that is what a Bukkit plugin took. They
 * are translated here rather than asking anybody to rewrite 7779 lines of dialogue.
 */
public final class Text {
    private Text() {
    }

    /** Parses a legacy {@code &}-coded string into a component, keeping colours and styles as they run. */
    public static Component legacy(String raw) {
        if (raw == null || raw.isEmpty())
            return Component.empty();
        MutableComponent result = Component.empty();
        StringBuilder buffer = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < raw.length()) {
                ChatFormatting format = ChatFormatting.getByCode(Character.toLowerCase(raw.charAt(i + 1)));
                if (format != null) {
                    if (buffer.length() > 0) {
                        result.append(Component.literal(buffer.toString()).withStyle(style));
                        buffer.setLength(0);
                    }
                    // a colour resets styles, exactly as the legacy format behaves
                    style = format == ChatFormatting.RESET ? Style.EMPTY
                            : format.isColor() ? Style.EMPTY.withColor(format) : style.applyFormat(format);
                    i++;
                    continue;
                }
            }
            buffer.append(c);
        }
        if (buffer.length() > 0) {
            result.append(Component.literal(buffer.toString()).withStyle(style));
        }
        return result;
    }

    /**
     * Fills in the placeholders the migrated files actually use.
     * <p>
     * {@code %checkitem_…%} is not handled here: those are conditions and item removals, not text, and
     * {@link CheckItem} owns them.
     */
    public static String placeholders(String raw, ServerPlayer player) {
        if (raw == null || raw.indexOf('%') < 0)
            return raw;
        String name = player == null ? "" : player.getGameProfile().getName();
        String result = raw.replace("%player%", name).replace("%player_name%", name);
        if (result.contains("%player_level%")) {
            result = result.replace("%player_level%", player == null ? "0" : String.valueOf(player.experienceLevel));
        }
        return result;
    }

    /** Splits {@code a;b;c} argument lists, which is how every action encodes its parameters. */
    public static List<String> semicolons(String raw) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            if (i == raw.length() || raw.charAt(i) == ';') {
                parts.add(raw.substring(start, i));
                start = i + 1;
            }
        }
        return parts;
    }

    /** @return the plain text of a component, for substring matching against item lore */
    public static String plain(Component component) {
        return component == null ? "" : component.getString();
    }
}
