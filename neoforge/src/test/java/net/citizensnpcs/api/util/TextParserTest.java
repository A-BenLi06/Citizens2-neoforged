package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Covers the tag syntax {@link TextParser} accepts in place of Adventure's MiniMessage.
 * <p>
 * The point of most of these is that a tag must never reach the player as literal text — an NPC named
 * {@code <gradient:red:blue>Bob</gradient>} showing up with the angle brackets visible is the failure mode being
 * guarded against.
 */
public class TextParserTest {
    /** Flattens a parsed component into (text, style) pairs, one per literal sibling. */
    private static List<Style> stylesOf(String raw) {
        List<Style> styles = new ArrayList<>();
        collect(TextParser.parse(raw), styles);
        return styles;
    }

    private static void collect(Component component, List<Style> out) {
        if (!component.getString().isEmpty() && component.getContents() != net.minecraft.network.chat.contents.PlainTextContents.EMPTY) {
            out.add(component.getStyle());
        }
        for (Component sibling : component.getSiblings()) {
            collect(sibling, out);
        }
    }

    @Test
    public void namedAndHexColoursApply() {
        assertEquals("hello", TextParser.parse("<red>hello").getString());
        List<Style> styles = stylesOf("<red>hello");
        assertEquals(1, styles.size());
        assertEquals(ChatFormatting.RED.getColor().intValue(), styles.get(0).getColor().getValue());

        styles = stylesOf("<#ff8800>hi");
        assertEquals(0xff8800, styles.get(0).getColor().getValue());
    }

    @Test
    public void decorationsOpenAndClose() {
        List<Style> styles = stylesOf("<b>bold</b>plain");
        assertEquals(2, styles.size());
        assertTrue(styles.get(0).isBold());
        assertFalse(styles.get(1).isBold(), "closing tag must revert to the enclosing style");
    }

    @Test
    public void gradientColoursEveryCharacter() {
        String raw = "<gradient:#ff0000:#0000ff>abcd</gradient>";
        assertEquals("abcd", TextParser.parse(raw).getString(), "tag must not leak into the rendered text");

        List<Style> styles = stylesOf(raw);
        assertEquals(4, styles.size(), "one run per character");
        assertEquals(0xff0000, styles.get(0).getColor().getValue(), "first character is the first stop");
        assertEquals(0x0000ff, styles.get(3).getColor().getValue(), "last character is the last stop");
        assertNotEquals(styles.get(0).getColor(), styles.get(1).getColor(), "colour must move across the span");
    }

    @Test
    public void gradientHonoursMultipleStops() {
        List<Style> styles = stylesOf("<gradient:#ff0000:#00ff00:#0000ff>abcde</gradient>");
        assertEquals(5, styles.size());
        assertEquals(0xff0000, styles.get(0).getColor().getValue());
        assertEquals(0x00ff00, styles.get(2).getColor().getValue(), "middle stop lands on the middle character");
        assertEquals(0x0000ff, styles.get(4).getColor().getValue());
    }

    @Test
    public void rainbowColoursEveryCharacter() {
        String raw = "<rainbow>abcdef</rainbow>";
        assertEquals("abcdef", TextParser.parse(raw).getString());
        List<Style> styles = stylesOf(raw);
        assertEquals(6, styles.size());
        assertNotEquals(styles.get(0).getColor(), styles.get(3).getColor());
    }

    @Test
    public void clickEventIsBuilt() {
        String raw = "<click:run_command:/npc list>click me</click>";
        assertEquals("click me", TextParser.parse(raw).getString());
        ClickEvent event = stylesOf(raw).get(0).getClickEvent();
        assertNotNull(event);
        assertEquals(ClickEvent.Action.RUN_COMMAND, event.getAction());
        assertEquals("/npc list", event.getValue(), "a value containing a slash must survive");
    }

    @Test
    public void hoverEventIsBuilt() {
        String raw = "<hover:show_text:'tip: hello'>text</hover>";
        assertEquals("text", TextParser.parse(raw).getString());
        HoverEvent event = stylesOf(raw).get(0).getHoverEvent();
        assertNotNull(event);
        assertEquals(HoverEvent.Action.SHOW_TEXT, event.getAction());
        assertEquals("tip: hello", event.getValue(HoverEvent.Action.SHOW_TEXT).getString(),
                "a quoted value containing a colon must survive");
    }

    @Test
    public void fontIsBuilt() {
        assertEquals("uniform", TextParser.parse("<font:minecraft:uniform>x</font>").getString().isEmpty() ? "" : "uniform");
        assertEquals(net.minecraft.resources.ResourceLocation.withDefaultNamespace("uniform"),
                stylesOf("<font:minecraft:uniform>x</font>").get(0).getFont());
    }

    @Test
    public void unknownTagsStayLiteral() {
        // better a visible stray tag than silently swallowed text
        assertEquals("<notatag>kept", TextParser.parse("<notatag>kept").getString());
        assertEquals("a < b", TextParser.parse("a < b").getString());
    }

    @Test
    public void unsupportedMiniMessageTagsStayLiteral() {
        assertEquals("<selector:@p>x", TextParser.parse("<selector:@p>x").getString());
    }

    @Test
    public void legacyCodesStillWork() {
        List<Style> styles = stylesOf("§cred§lbold");
        assertEquals(2, styles.size());
        assertEquals(ChatFormatting.RED.getColor().intValue(), styles.get(0).getColor().getValue());
        assertTrue(styles.get(1).isBold());
    }

    @Test
    public void csrClearsDecorationsButKeepsColour() {
        List<Style> styles = stylesOf("<red><b>x<csr>y");
        Style after = styles.get(styles.size() - 1);
        assertFalse(after.isBold(), "csr clears decorations");
        assertEquals(ChatFormatting.RED.getColor().intValue(), after.getColor().getValue(), "csr keeps the colour");
    }

    @Test
    public void stripRemovesEveryTag() {
        assertEquals("abcd", TextParser.strip("<gradient:#ff0000:#0000ff>abcd</gradient>"));
        assertEquals("click me", TextParser.strip("<click:run_command:/npc list>click me</click>"));
        assertEquals("red bold", TextParser.strip("§cred <b>bold</b>"));
    }

    @Test
    public void toLegacyDegradesHexToNearestColour() {
        // an exact match must round-trip: ChatFormatting.RED is 0xff5555
        assertEquals("§" + ChatFormatting.RED.getChar() + "red", TextParser.toLegacy("<#ff5555>red"));

        // pure #ff0000 has no exact legacy equivalent; nearest by RGB distance is dark red (0xaa0000, 85² away)
        // rather than red (0xff5555, 85²+85² away)
        assertEquals("§" + ChatFormatting.DARK_RED.getChar() + "red", TextParser.toLegacy("<#ff0000>red"));

        // whatever the colour resolves to, the text itself must never be lost
        String legacy = TextParser.toLegacy("<gradient:#ff0000:#0000ff>abcd</gradient>");
        assertEquals("abcd", legacy.replaceAll("§.", ""));
    }

    @Test
    public void brBecomesNewline() {
        assertEquals("a\nb", TextParser.parse("a<br>b").getString());
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", TextParser.parse(null).getString());
        assertEquals("", TextParser.parse("").getString());
        assertNull(TextParser.strip(null));
    }
}
