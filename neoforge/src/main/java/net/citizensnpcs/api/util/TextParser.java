package net.citizensnpcs.api.util;

import java.util.Arrays;
import java.util.Optional;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/** Citizens' MiniMessage grammar with native Minecraft component output and explicit source-aware resolution. */
public class TextParser {
    private TextParser() {
    }

    /** Parses text without resolving selectors, scores or NBT against a command source. */
    public static MutableComponent parse(String raw) {
        if (raw == null || raw.isEmpty())
            return Component.empty();
        try {
            String normalized = LEGACY_CODES.matcher(raw).replaceAll(match -> Messaging.convertLegacyCodes(match.group()));
            return MiniMessageComponents.convert(MINIMESSAGE.deserialize(normalized));
        } catch (RuntimeException ex) {
            // An unavailable registry payload or a component newer than the target protocol must not silently lose
            // its data. Keep the input visible and editable, as MiniMessage does for unrecognized tags.
            Messaging.debug("Unable to represent MiniMessage as a native component:", ex.getMessage());
            return Component.literal(raw);
        }
    }

    /** Resolves dynamic native components using the caller's actual command source and permissions. */
    public static MutableComponent parse(String raw, CommandSourceStack source) {
        try {
            return ComponentUtils.updateForEntity(source, parse(raw), source.getEntity(), 0);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            Messaging.debug("Unable to resolve native text component:", ex.getMessage());
            return Component.literal(raw == null ? "" : raw);
        }
    }

    /** Plain native text; unknown tags remain visible, and unresolved dynamic content retains native semantics. */
    public static String strip(String raw) {
        return raw == null ? null : parse(raw).getString();
    }

    /** Legacy projection with RGB section-sign sequences, matching modern Citizens; events and non-text data are omitted. */
    public static String toLegacy(String raw) {
        if (raw == null)
            return null;
        StringBuilder text = new StringBuilder();
        Style[] previous = { Style.EMPTY };
        parse(raw).visit((style, value) -> {
            if (!value.isEmpty()) {
                appendLegacyCodes(text, previous[0], style);
                text.append(value);
                previous[0] = style;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return text.toString();
    }

    private static void appendLegacyCodes(StringBuilder sb, Style previous, Style current) {
        boolean lostDecoration = previous.isBold() && !current.isBold() || previous.isItalic() && !current.isItalic()
                || previous.isUnderlined() && !current.isUnderlined()
                || previous.isStrikethrough() && !current.isStrikethrough()
                || previous.isObfuscated() && !current.isObfuscated();
        if (lostDecoration || previous.getColor() != null && current.getColor() == null) {
            sb.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.RESET.getChar());
            previous = Style.EMPTY;
        }
        TextColor colour = current.getColor();
        if (colour != null && !colour.equals(previous.getColor())) {
            ChatFormatting legacy = Arrays.stream(ChatFormatting.values())
                    .filter(value -> value.isColor() && value.getColor() == colour.getValue()).findFirst().orElse(null);
            if (legacy != null) {
                sb.append(ChatFormatting.PREFIX_CODE).append(legacy.getChar());
            } else {
                sb.append(ChatFormatting.PREFIX_CODE).append('x');
                for (int shift = 20; shift >= 0; shift -= 4) {
                    sb.append(ChatFormatting.PREFIX_CODE).append(Character.forDigit(colour.getValue() >> shift & 15, 16));
                }
            }
            previous = Style.EMPTY;
        }
        appendIfNewlySet(sb, previous.isBold(), current.isBold(), ChatFormatting.BOLD);
        appendIfNewlySet(sb, previous.isItalic(), current.isItalic(), ChatFormatting.ITALIC);
        appendIfNewlySet(sb, previous.isUnderlined(), current.isUnderlined(), ChatFormatting.UNDERLINE);
        appendIfNewlySet(sb, previous.isStrikethrough(), current.isStrikethrough(), ChatFormatting.STRIKETHROUGH);
        appendIfNewlySet(sb, previous.isObfuscated(), current.isObfuscated(), ChatFormatting.OBFUSCATED);
    }

    private static void appendIfNewlySet(StringBuilder sb, boolean was, boolean is, ChatFormatting code) {
        if (is && !was) {
            sb.append(ChatFormatting.PREFIX_CODE).append(code.getChar());
        }
    }

    private static final java.util.regex.Pattern LEGACY_CODES = java.util.regex.Pattern
            .compile("§x(?:§[0-9a-f]){6}|§[0-9a-fk-or]", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final MiniMessage MINIMESSAGE = MiniMessage.builder().editTags(tags -> tags.resolver(
            TagResolver.resolver("csr", Tag.styling(style -> Arrays.stream(TextDecoration.values())
                    .forEach(decoration -> style.decoration(decoration, false)))))).build();
}
