package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DialogueWriterTest {
    @TempDir Path directory;

    @Test void readsLegacySettingsAndRejectsMalformedSnapshots() throws Exception {
        assertFalse(DialogueSettings.DEFAULT.actionBar());
        assertEquals(new WriteDialogueSettings(false, WriteDialogueSettings.Mode.WORD, 2), DialogueSettings.DEFAULT.writeDialogues());
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "action_bar: true\nwrite_dialogues: {enabled: true, mode: character, delay: 3}\n");
        var settings = DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT);
        assertTrue(settings.actionBar());
        assertEquals(new WriteDialogueSettings(true, WriteDialogueSettings.Mode.CHARACTER, 3), settings.writeDialogues());
        for (String invalid : List.of("action_bar: yes please", "write_dialogues: wrong", "write_dialogues: {mode: missing}",
                "write_dialogues: {delay: 0}", "write_dialogues: {delay: -1}", "write_dialogues: {delay: 1.5}",
                "write_dialogues: {delay: 2147483648}", "write_dialogues: {enabled: maybe}")) {
            Files.writeString(file, invalid);
            assertSame(settings, DialogueSettings.load(file.toFile(), settings), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> WriteDialogueSettings.read(Map.of("delay", Double.NaN)));
    }

    @Test void loadsIndependentStatusTitles() throws Exception {
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "actionBarTitleConversation: '&aTalk %name%'\nactionBarTitleSelectOption: '&bChoose %name%'\n");
        var messages = DialogueMessages.load(file.toFile(), DialogueMessages.DEFAULT);
        assertEquals("Talk NPC", messages.actionBarTitle("{centered}&eNPC", false).getString());
        assertEquals("Choose NPC", messages.actionBarTitle("{centered}&eNPC", true).getString());
        assertTrue(messages.bossBarTitle("NPC", false).getString().startsWith("Currently"));
        assertInstanceOf(TranslatableContents.class, DialogueMessages.DEFAULT.actionBarTitle("NPC", false).getContents());
        Files.writeString(file, "actionBarTitleSelectOption: [invalid]\n");
        assertSame(messages, DialogueMessages.load(file.toFile(), messages));
    }

    @Test void characterStepsKeepUnicodeAndFormattingWithExactTickDelay() {
        var writer = new DialogueWriter(List.of(Text.legacy("&a你😀&l好")),
                new WriteDialogueSettings(true, WriteDialogueSettings.Mode.CHARACTER, 2));
        assertEquals("你", writer.tick().getFirst().getString());
        assertNull(writer.tick());
        var middle = writer.tick().getFirst();
        assertEquals("你😀", middle.getString());
        assertEquals(ChatFormatting.GREEN.getColor(), middle.toFlatList().getFirst().getStyle().getColor().getValue());
        assertNull(writer.tick());
        var last = writer.tick().getFirst();
        assertEquals("你😀好", last.getString());
        assertTrue(last.toFlatList().getLast().getStyle().isBold());
        for (int i = 0; i < 5; i++) assertNull(writer.tick());
    }

    @Test void wordStepsKeepWhitespaceAndCrossFormattingBoundaries() {
        var writer = writer(WriteDialogueSettings.Mode.WORD, Text.legacy("  hel&blo\t  world  "));
        assertEquals("  hello", writer.tick().getFirst().getString());
        assertEquals("  hello\t  world  ", writer.tick().getFirst().getString());
        assertNull(writer.tick());
    }

    @Test void previousRowsAndEmptyRowsSurviveProgression() {
        var writer = writer(WriteDialogueSettings.Mode.CHARACTER, Component.literal("A"), Component.empty(), Component.literal("B"));
        assertEquals(List.of("A"), strings(writer.tick()));
        assertEquals(List.of("A", ""), strings(writer.tick()));
        assertEquals(List.of("A", "", "B"), strings(writer.tick()));
        assertNull(writer.tick());
        assertNull(writer(WriteDialogueSettings.Mode.WORD).tick());
    }

    @Test void nextControlAppearsAtomicallyWithItsNativeLabelAndEvents() {
        var control = Component.translatableWithFallback("test.next", "Next please").withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/interactions skipdialogue"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Continue"))));
        var writer = writer(WriteDialogueSettings.Mode.CHARACTER, Component.literal("A").append(control));
        assertEquals("A", writer.tick().getFirst().getString());
        var frame = writer.tick().getFirst();
        assertEquals("ANext please", frame.getString());
        assertEquals(control, frame.getSiblings().getFirst());
        assertNull(writer.tick());
    }

    @Test void preservesJsonStructureInheritedStylesAndClientTranslations() {
        Component source = Text.json("""
                {"text":"ab","color":"gold","bold":true,"hoverEvent":{"action":"show_text","contents":"Help"},
                 "extra":[{"translate":"test.dynamic","fallback":"Localized body"},{"text":"cd","italic":true}]}
                """, null);
        var writer = writer(WriteDialogueSettings.Mode.CHARACTER, source);
        Component first = writer.tick().getFirst();
        assertEquals("a", first.getString());
        assertTrue(first.toFlatList().getFirst().getStyle().isBold());
        assertNotNull(first.toFlatList().getFirst().getStyle().getHoverEvent());
        writer.tick();
        Component translated = writer.tick().getFirst();
        assertTrue(translated.getSiblings().stream().anyMatch(c -> c.getContents() instanceof TranslatableContents));
        assertEquals("abLocalized body", translated.getString());
        Component partialTail = writer.tick().getFirst();
        assertEquals("abLocalized bodyc", partialTail.getString());
        assertTrue(partialTail.toFlatList().getLast().getStyle().isItalic());
        assertEquals(source, writer.tick().getFirst());
    }

    private static DialogueWriter writer(WriteDialogueSettings.Mode mode, Component... rows) {
        return new DialogueWriter(List.of(rows), new WriteDialogueSettings(true, mode, 1));
    }
    private static List<String> strings(List<Component> frame) { return frame.stream().map(Component::getString).toList(); }
}
