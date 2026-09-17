package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InlineDialogueTest {
    @TempDir Path directory;

    @Test void loaderReadsNodeScopedInlineOptionsAndInterruptActions() throws Exception {
        Files.writeString(directory.resolve("inline.yml"), """
                starts_with: [NPC with id 91]
                conversation:
                  conversation1:
                    options_in_dialogue: true
                    interrupt_actions: ['console_command: say Interrupted']
                    dialogue:
                      dialogue1: {text: ['Select %option_1%'], time: 1}
                    options:
                      option1: {text: First}
                  conversation2:
                    dialogue:
                      dialogue1: {text: [Next], time: 1}
                """);
        var library = new ConversationLibrary(); library.load(directory.toFile());
        var first = library.forNpc(91).first();
        assertTrue(first.optionsInDialogue);
        assertEquals(List.of("console_command: say Interrupted"), first.interruptActions);
        var second = library.forNpc(91).node("conversation2");
        assertFalse(second.optionsInDialogue); assertTrue(second.interruptActions.isEmpty());
    }

    @Test void controlsRetainSurroundingStyleSuffixAndMultipleMarkers() {
        Component text = Text.legacy("&aBefore %next% after %option_1% tail", marker -> switch (marker) {
            case "%next%" -> Component.literal("Next").withStyle(ChatFormatting.GOLD);
            case "%option_1%" -> Component.literal("Choice");
            default -> null;
        });
        assertEquals("Before Next after Choice tail", text.getString());
        var flattened = text.toFlatList();
        assertTrue(flattened.stream().filter(c -> c.getString().equals("Next"))
                .allMatch(c -> c.getStyle().getColor().getValue() == ChatFormatting.GOLD.getColor()));
        assertEquals(ChatFormatting.GREEN.getColor(), flattened.getLast().getStyle().getColor().getValue());
    }

    @Test void unknownPercentMarkersAreNotConsumedByControlParsing() {
        assertEquals("%external% Next 50%", Text.legacy("%external% %next% 50%",
                marker -> marker.equals("%next%") ? Component.literal("Next") : null).getString());
    }

    @Test void explicitlyDeclaredNonClickableOptionsAreAtomicDuringTyping() {
        var controls = new ArrayList<Component>();
        Component text = Text.legacy("%option_1%Z", marker -> {
            Component label = Component.literal("Selected option"); controls.add(label); return label;
        });
        var writer = new DialogueWriter(List.of(text), new WriteDialogueSettings(true, WriteDialogueSettings.Mode.CHARACTER, 1), controls);
        assertEquals("Selected option", writer.tick().getFirst().getString());
        assertEquals("Selected optionZ", writer.tick().getFirst().getString());
        assertNull(writer.tick());
    }

    @Test void originalUseOptionCommandIsAllowedThroughDialogueRestrictions() {
        assertTrue(DialogueSettings.DEFAULT.permitsCommand("interactions useoption 10"));
        assertTrue(DialogueSettings.DEFAULT.permitsCommand("interactions choose 1 view-token"));
        assertFalse(DialogueSettings.DEFAULT.permitsCommand("interactions useoptionother"));
    }
}
