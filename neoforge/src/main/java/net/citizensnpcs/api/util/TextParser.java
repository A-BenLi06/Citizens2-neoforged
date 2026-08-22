package net.citizensnpcs.api.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

/**
 * Parser for the tag syntax Citizens uses, producing Minecraft {@link Component}s.
 * <p>
 * Upstream Citizens2 routes every message through Adventure's MiniMessage. That dependency is dropped in the NeoForge
 * port, so this class reimplements the syntax against Minecraft's own {@link Style}, which supports all of it natively.
 * Supported:
 * <ul>
 * <li>named colours — {@code <red>}, {@code <dark_aqua>}, … and hex {@code <#ff8800>}
 * <li>decorations — {@code <b> <i> <u> <st> <obf>} and long forms, with {@code </b>}-style closing tags
 * <li>{@code <gradient:#ff0000:#00ff00>text</gradient>} — any number of stops, interpolated per character
 * <li>{@code <rainbow>text</rainbow>} and {@code <rainbow:0.5>} for a phase offset
 * <li>{@code <click:run_command:/npc list>text</click>} — all five vanilla click actions
 * <li>{@code <hover:show_text:'tooltip'>text</hover>}
 * <li>{@code <font:minecraft:uniform>text</font>}
 * <li>{@code <reset>}, {@code <csr>} (Citizens' decoration reset), {@code <br>}, and legacy {@code §} codes
 * </ul>
 * Tags nest and close by name; an unrecognised or unmatched tag is emitted as literal text rather than swallowed, so a
 * stray {@code <} in an NPC name survives.
 * <p>
 * Not supported: MiniMessage's {@code <hover:show_item>}/{@code <show_entity>}, {@code <insert>}, {@code <selector>},
 * {@code <score>}, {@code <keybind>}, {@code <translate>} and placeholder resolvers. These render as literal text.
 */
public class TextParser {
    private TextParser() {
    }

    /**
     * @return {@code raw} rendered as a Component; never null, empty input yields an empty component
     */
    public static MutableComponent parse(String raw) {
        MutableComponent root = Component.empty();
        if (raw == null || raw.isEmpty())
            return root;

        for (Run run : scan(raw, false)) {
            root.append(Component.literal(run.text).withStyle(run.style));
        }
        return root;
    }

    /** @return {@code raw} with every tag and legacy colour code removed */
    public static String strip(String raw) {
        if (raw == null)
            return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (Run run : scan(raw, true)) {
            sb.append(run.text);
        }
        return sb.toString();
    }

    /**
     * @return {@code raw} rendered back to a legacy {@code §} string, for the few call sites that need plain text with
     *         colour codes. Lossy by nature: hex and gradient colours collapse to the nearest of the sixteen legacy
     *         colours, and click/hover/font are dropped, because the legacy format cannot express them.
     */
    public static String toLegacy(String raw) {
        if (raw == null)
            return null;
        StringBuilder sb = new StringBuilder(raw.length());
        Style previous = Style.EMPTY;
        for (Run run : scan(raw, false)) {
            appendLegacyCodes(sb, previous, run.style);
            sb.append(run.text);
            previous = run.style;
        }
        return sb.toString();
    }

    private static void appendLegacyCodes(StringBuilder sb, Style previous, Style current) {
        boolean lostDecoration = previous.isBold() && !current.isBold() || previous.isItalic() && !current.isItalic()
                || previous.isUnderlined() && !current.isUnderlined()
                || previous.isStrikethrough() && !current.isStrikethrough()
                || previous.isObfuscated() && !current.isObfuscated();
        if (lostDecoration) {
            sb.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.RESET.getChar());
            previous = Style.EMPTY;
        }
        TextColor colour = current.getColor();
        if (colour != null && !colour.equals(previous.getColor())) {
            ChatFormatting legacy = nearestLegacyColour(colour);
            if (legacy != null) {
                sb.append(ChatFormatting.PREFIX_CODE).append(legacy.getChar());
            }
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

    /** Closest of the sixteen legacy colours by squared RGB distance, so hex and gradients degrade sensibly. */
    private static ChatFormatting nearestLegacyColour(TextColor colour) {
        int rgb = colour.getValue();
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        ChatFormatting best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ChatFormatting formatting : ChatFormatting.values()) {
            if (!formatting.isColor())
                continue;
            Integer value = formatting.getColor();
            if (value == null)
                continue;
            int dr = (value >> 16 & 0xFF) - r, dg = (value >> 8 & 0xFF) - g, db = (value & 0xFF) - b;
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = formatting;
            }
        }
        return best;
    }

    /**
     * Single pass over {@code raw}, splitting it into style-homogeneous runs. While a gradient or rainbow span is open
     * each character becomes its own run, since its colour changes every character.
     *
     * @param stripOnly
     *            when true styles are still tracked but never recorded, so tags vanish from the output
     */
    private static List<Run> scan(String raw, boolean stripOnly) {
        List<Run> runs = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Deque<OpenTag> open = new ArrayDeque<>();
        Style style = Style.EMPTY;
        int i = 0, length = raw.length();

        while (i < length) {
            char c = raw.charAt(i);

            if (c == ChatFormatting.PREFIX_CODE && i + 1 < length) {
                ChatFormatting formatting = ChatFormatting.getByCode(raw.charAt(i + 1));
                if (formatting != null) {
                    Style updated = applyFormatting(style, formatting);
                    if (updated != style) {
                        flush(runs, text, style, stripOnly);
                        style = updated;
                    }
                    i += 2;
                    continue;
                }
            }

            if (c == '<') {
                int close = raw.indexOf('>', i + 1);
                if (close > i) {
                    String tag = raw.substring(i + 1, close);
                    if (tag.equals("br")) {
                        text.append('\n');
                        i = close + 1;
                        continue;
                    }
                    if (tag.startsWith("/")) {
                        String name = tagName(tag.substring(1));
                        Style reverted = unwind(open, name);
                        if (reverted != null) {
                            flush(runs, text, style, stripOnly);
                            style = reverted;
                            i = close + 1;
                            continue;
                        }
                        // unmatched close tag - fall through and emit it literally
                    } else {
                        String name = tagName(tag);
                        ColourSpan span = null;
                        if (name.equals("gradient") || name.equals("rainbow")) {
                            span = createColourSpan(name, tag, plainLengthUntilClose(raw, close + 1, name));
                        }
                        Style updated = span != null ? style : applyTag(style, tag, name);
                        if (updated != null) {
                            flush(runs, text, style, stripOnly);
                            open.push(new OpenTag(name, style, span));
                            style = updated;
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }

            ColourSpan span = activeSpan(open);
            if (span != null && !stripOnly) {
                // per-character colouring: flush anything buffered, then emit this char on its own
                flush(runs, text, style, stripOnly);
                runs.add(new Run(String.valueOf(c), style.withColor(span.next())));
            } else {
                if (span != null) {
                    span.next();
                }
                text.append(c);
            }
            i++;
        }
        flush(runs, text, style, stripOnly);
        return runs;
    }

    /** @return the innermost open gradient/rainbow span, or null */
    private static ColourSpan activeSpan(Deque<OpenTag> open) {
        for (OpenTag tag : open) {
            if (tag.span != null)
                return tag.span;
        }
        return null;
    }

    /**
     * Pops open tags until {@code name} is found, returning the style that was in effect before it opened. Returns null
     * without touching the stack if there is no matching open tag.
     */
    private static Style unwind(Deque<OpenTag> open, String name) {
        boolean present = false;
        for (OpenTag tag : open) {
            if (tag.name.equals(name)) {
                present = true;
                break;
            }
        }
        if (!present)
            return null;
        OpenTag tag;
        do {
            tag = open.pop();
        } while (!tag.name.equals(name));
        return tag.savedStyle;
    }

    /** Plain-text length of the span opened at {@code from}, up to its matching close tag or end of input. */
    private static int plainLengthUntilClose(String raw, int from, String tagName) {
        int count = 0, depth = 1, i = from, length = raw.length();
        while (i < length) {
            char c = raw.charAt(i);
            if (c == ChatFormatting.PREFIX_CODE && i + 1 < length && ChatFormatting.getByCode(raw.charAt(i + 1)) != null) {
                i += 2;
                continue;
            }
            if (c == '<') {
                int close = raw.indexOf('>', i + 1);
                if (close > i) {
                    String tag = raw.substring(i + 1, close);
                    if (tag.equals("br")) {
                        count++;
                    } else if (tag.startsWith("/")) {
                        if (tagName(tag.substring(1)).equals(tagName) && --depth == 0)
                            return count;
                    } else if (tagName(tag).equals(tagName)) {
                        depth++;
                    }
                    i = close + 1;
                    continue;
                }
            }
            count++;
            i++;
        }
        return count;
    }

    private static ColourSpan createColourSpan(String name, String tag, int length) {
        List<String> args = splitTagArgs(tag);
        if (name.equals("rainbow")) {
            float phase = 0;
            if (args.size() > 1) {
                try {
                    phase = Float.parseFloat(args.get(1));
                } catch (NumberFormatException e) {
                    // a malformed phase is not worth rejecting the whole tag over
                }
            }
            return new ColourSpan(null, phase, length);
        }
        List<TextColor> stops = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            TextColor stop = parseColour(args.get(i));
            if (stop != null) {
                stops.add(stop);
            }
        }
        if (stops.size() < 2) {
            // <gradient> with no usable stops defaults to white -> black, matching MiniMessage
            stops.clear();
            stops.add(TextColor.fromRgb(0xFFFFFF));
            stops.add(TextColor.fromRgb(0x000000));
        }
        return new ColourSpan(stops.toArray(new TextColor[0]), 0, length);
    }

    private static void flush(List<Run> runs, StringBuilder text, Style style, boolean stripOnly) {
        if (text.length() == 0)
            return;
        runs.add(new Run(text.toString(), stripOnly ? Style.EMPTY : style));
        text.setLength(0);
    }

    private static Style applyFormatting(Style style, ChatFormatting formatting) {
        if (formatting == ChatFormatting.RESET)
            return Style.EMPTY;
        if (formatting.isColor())
            return style.withColor(formatting);
        switch (formatting) {
            case BOLD:
                return style.withBold(true);
            case ITALIC:
                return style.withItalic(true);
            case UNDERLINE:
                return style.withUnderlined(true);
            case STRIKETHROUGH:
                return style.withStrikethrough(true);
            case OBFUSCATED:
                return style.withObfuscated(true);
            default:
                return style;
        }
    }

    /**
     * @return the updated style, or null if {@code tag} is not one we understand (the caller then emits it literally)
     */
    private static Style applyTag(Style style, String tag, String name) {
        if (name.isEmpty())
            return null;
        if (name.equals("reset"))
            return Style.EMPTY;
        if (name.equals("csr"))
            // "Citizens style reset": clears decorations but keeps the current colour
            return Style.EMPTY.withColor(style.getColor()).withBold(false).withItalic(false).withUnderlined(false)
                    .withStrikethrough(false).withObfuscated(false);

        switch (name) {
            case "b":
            case "bold":
                return style.withBold(true);
            case "i":
            case "italic":
            case "em":
                return style.withItalic(true);
            case "u":
            case "underlined":
                return style.withUnderlined(true);
            case "st":
            case "strikethrough":
                return style.withStrikethrough(true);
            case "obf":
            case "obfuscated":
                return style.withObfuscated(true);
            case "click":
                return applyClick(style, tag);
            case "hover":
                return applyHover(style, tag);
            case "font":
                return applyFont(style, tag);
            default:
                break;
        }
        TextColor colour = parseColour(name);
        return colour == null ? null : style.withColor(colour);
    }

    private static Style applyClick(Style style, String tag) {
        List<String> args = splitTagArgs(tag);
        if (args.size() < 3)
            return null;
        ClickEvent.Action action = null;
        String wanted = args.get(1).toLowerCase(Locale.ROOT);
        for (ClickEvent.Action candidate : ClickEvent.Action.values()) {
            if (candidate.getSerializedName().equals(wanted)) {
                action = candidate;
                break;
            }
        }
        if (action == null)
            return null;
        String value = unquote(String.join(":", args.subList(2, args.size())));
        return style.withClickEvent(new ClickEvent(action, value));
    }

    private static Style applyFont(Style style, String tag) {
        List<String> args = splitTagArgs(tag);
        if (args.size() < 2)
            return null;
        ResourceLocation font = ResourceLocation.read(String.join(":", args.subList(1, args.size()))).result()
                .orElse(null);
        return font == null ? null : style.withFont(font);
    }

    private static Style applyHover(Style style, String tag) {
        List<String> args = splitTagArgs(tag);
        if (args.size() < 3 || !args.get(1).equalsIgnoreCase("show_text"))
            // show_item and show_entity need a registry-aware payload; leave them as literal text
            return null;
        String value = unquote(String.join(":", args.subList(2, args.size())));
        return style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, parse(value)));
    }

    private static TextColor parseColour(String name) {
        if (name.isEmpty())
            return null;
        if (name.charAt(0) == '#') {
            if (name.length() != 7)
                return null;
            try {
                return TextColor.fromRgb(Integer.parseInt(name.substring(1), 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        ChatFormatting formatting = NAMED_COLOURS.get(name.toLowerCase(Locale.ROOT));
        return formatting == null ? null : TextColor.fromLegacyFormat(formatting);
    }

    /** Splits a tag body on ':', leaving quoted sections intact so hover text may contain colons. */
    private static List<String> splitTagArgs(String tag) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ':') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static String tagName(String tag) {
        int colon = tag.indexOf(':');
        return (colon < 0 ? tag : tag.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.charAt(0) == '\'' || value.charAt(0) == '"')
                && value.charAt(value.length() - 1) == value.charAt(0))
            return value.substring(1, value.length() - 1);
        return value;
    }

    /** A run of text sharing one style. */
    private static class Run {
        private final Style style;
        private final String text;

        private Run(String text, Style style) {
            this.text = text;
            this.style = style;
        }
    }

    /** An open tag awaiting its closing counterpart. */
    private static class OpenTag {
        private final String name;
        private final Style savedStyle;
        private final ColourSpan span;

        private OpenTag(String name, Style savedStyle, ColourSpan span) {
            this.name = name;
            this.savedStyle = savedStyle;
            this.span = span;
        }
    }

    /** Per-character colouring for {@code <gradient>} and {@code <rainbow>}. */
    private static class ColourSpan {
        private int index;
        private final int length;
        private final float phase;
        private final TextColor[] stops;

        private ColourSpan(TextColor[] stops, float phase, int length) {
            this.stops = stops;
            this.phase = phase;
            this.length = Math.max(1, length);
        }

        private TextColor next() {
            float t = length <= 1 ? 0F : (float) index / (length - 1);
            index++;
            if (stops == null)
                return TextColor.fromRgb(hsbToRgb((phase + t) % 1.0F, 1F, 1F));

            float scaled = t * (stops.length - 1);
            int i = Math.min((int) scaled, stops.length - 2);
            return TextColor.fromRgb(lerp(stops[i].getValue(), stops[i + 1].getValue(), scaled - i));
        }

        private static int lerp(int from, int to, float t) {
            int r = Math.round((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
            int g = Math.round((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
            int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
            return r << 16 | g << 8 | b;
        }

        /** Hand-rolled rather than using {@code java.awt.Color}, which has no place on a headless server. */
        private static int hsbToRgb(float hue, float saturation, float brightness) {
            float h = (hue - (float) Math.floor(hue)) * 6F;
            float f = h - (float) Math.floor(h);
            float p = brightness * (1 - saturation);
            float q = brightness * (1 - saturation * f);
            float t = brightness * (1 - saturation * (1 - f));
            float r, g, b;
            switch ((int) h) {
                case 0:
                    r = brightness; g = t; b = p;
                    break;
                case 1:
                    r = q; g = brightness; b = p;
                    break;
                case 2:
                    r = p; g = brightness; b = t;
                    break;
                case 3:
                    r = p; g = q; b = brightness;
                    break;
                case 4:
                    r = t; g = p; b = brightness;
                    break;
                default:
                    r = brightness; g = p; b = q;
                    break;
            }
            return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
        }
    }

    private static final Map<String, ChatFormatting> NAMED_COLOURS = new HashMap<>();
    static {
        for (ChatFormatting formatting : ChatFormatting.values()) {
            if (formatting.isColor()) {
                NAMED_COLOURS.put(formatting.getName().toLowerCase(Locale.ROOT), formatting);
            }
        }
        // MiniMessage spells these differently to ChatFormatting#getName
        NAMED_COLOURS.put("gray", ChatFormatting.GRAY);
        NAMED_COLOURS.put("grey", ChatFormatting.GRAY);
        NAMED_COLOURS.put("dark_gray", ChatFormatting.DARK_GRAY);
        NAMED_COLOURS.put("dark_grey", ChatFormatting.DARK_GRAY);
    }
}
