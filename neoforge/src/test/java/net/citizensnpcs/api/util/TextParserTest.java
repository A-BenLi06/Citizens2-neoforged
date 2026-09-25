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
    @Test
    public void quotedHoverPreservesNestedTagsAndClosingBrackets() {
        Component parsed = TextParser.parse("<hover:show_text:'<red>hello > world</red>'>label</hover>");
        assertEquals("label", parsed.getString());
        HoverEvent event = stylesOf("<hover:show_text:'<red>hello > world</red>'>label</hover>").get(0).getHoverEvent();
        assertNotNull(event);
        Component tooltip = event.getValue(HoverEvent.Action.SHOW_TEXT);
        assertEquals("hello > world", tooltip.getString());
    }

    /** Flattens a parsed component into (text, style) pairs, one per literal sibling. */
    private static List<Style> stylesOf(String raw) {
        List<Style> styles = new ArrayList<>();
        TextParser.parse(raw).visit((style, text) -> {
            if (!text.isEmpty()) styles.add(style);
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return styles;
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
    public void selectorPreservesNativeContents() {
        assertTrue(TextParser.parse("<selector:@p>").getContents()
                instanceof net.minecraft.network.chat.contents.SelectorContents);
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
    public void toLegacyPreservesHexAndGradientColours() {
        // an exact match must round-trip: ChatFormatting.RED is 0xff5555
        assertEquals("§" + ChatFormatting.RED.getChar() + "red", TextParser.toLegacy("<#ff5555>red"));

        assertEquals("§x§f§f§0§0§0§0red", TextParser.toLegacy("<#ff0000>red"));
        assertEquals(0xff0000, stylesOf(TextParser.toLegacy("<#ff0000>red")).getFirst().getColor().getValue());

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
    @Test
    public void insertionAndDecorationNegationPreserveOuterStyle() {
        List<Style> styles = stylesOf("<b><insert:'a:b'>yes<!b>no</!b>end</insert></b>");
        assertTrue(styles.getFirst().isBold());
        assertEquals("a:b", styles.getFirst().getInsertion());
        assertFalse(styles.get(1).isBold());
        assertTrue(styles.getLast().isBold());
    }

    @Test
    public void csrKeepsEventsAndFontWhileClearingDecorations() {
        Style style = stylesOf("<click:run_command:/npc list><font:minecraft:uniform><red><b>first<csr>last").getLast();
        assertFalse(style.isBold());
        assertEquals("/npc list", style.getClickEvent().getValue());
        assertEquals("minecraft:uniform", style.getFont().toString());
        assertEquals(ChatFormatting.RED.getColor().intValue(), style.getColor().getValue());
    }

    @Test
    public void unicodeGradientNeverSplitsSurrogatePairs() {
        String raw = "<gradient:red:blue>😀x</gradient>";
        assertEquals("😀x", TextParser.parse(raw).getString());
        assertEquals(2, stylesOf(raw).size());
    }

    @Test
    public void nativeKeybindAndTranslationRemainStructured() {
        assertTrue(TextParser.parse("<key:key.jump>").getContents()
                instanceof net.minecraft.network.chat.contents.KeybindContents);
        var translated = TextParser.parse("<lang:chat.type.text:'<red>Name':'Message'>");
        assertTrue(translated.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents);
        var contents = (net.minecraft.network.chat.contents.TranslatableContents) translated.getContents();
        assertEquals("chat.type.text", contents.getKey());
        assertEquals(2, contents.getArgs().length);
        assertEquals("Name", ((Component) contents.getArgs()[0]).getString());
    }

    @Test
    public void legacyProjectionResetsColourAndRestoresDecorations() {
        assertEquals("§cred§rplain", TextParser.toLegacy("<red>red</red>plain"));
        assertEquals("§lbold§rplain", TextParser.toLegacy("<b>bold</b>plain"));
    }

    @Test
    public void actualTextEditorPromptDoesNotLeakNestedHoverMarkup() throws Exception {
        try (var stream = TextParserTest.class.getResourceAsStream("/citizens/en.json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            String raw = json.getAsJsonObject().get("citizens.editors.text.start-prompt").getAsString();
            String text = TextParser.parse(raw).getString();
            assertTrue(text.contains("Add text"));
            assertFalse(text.contains("Set the talk item"));
            assertFalse(text.contains("</hover>"));
            assertFalse(text.contains("<yellow>"));
        }
    }

    @Test
    public void newerProtocolStyleRemainsEditableInsteadOfBeingDiscarded() {
        String raw = "<shadow:#112233>Text</shadow>";
        assertEquals(raw, TextParser.parse(raw).getString());
    }

    @Test
    public void rejectedNativeClickActionKeepsOriginalInput() {
        String raw = "<click:open_file:/example>Text</click>";
        assertEquals(raw, TextParser.parse(raw).getString());
    }

}
