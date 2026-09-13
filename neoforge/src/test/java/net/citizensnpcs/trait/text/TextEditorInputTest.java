package net.citizensnpcs.trait.text;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.command.exception.CommandException;

class TextEditorInputTest {
    @Test void commandWordParsingPreservesTheAuthoredPayload() {
        var input = TextBasePrompt.parts("  ADD <red>Title  §bbody --literal flag  ");
        assertEquals("ADD", input.word());
        assertEquals("<red>Title  §bbody --literal flag  ", input.tail());
        var edit = TextBasePrompt.parts("3   indented text");
        assertEquals("3", edit.word());
        assertEquals("  indented text", edit.tail());
        assertEquals("", TextBasePrompt.parts("add").tail());
    }

    @Test void bubbleDurationAcceptsNativeTicksAndUnits() throws Exception {
        assertEquals(Duration.ofMillis(1250), TextBasePrompt.parseBubbleDuration("25"));
        assertEquals(Duration.ofMillis(1250), TextBasePrompt.parseBubbleDuration("25t"));
        assertEquals(Duration.ofSeconds(2), TextBasePrompt.parseBubbleDuration("2s"));
        assertEquals(Duration.ZERO, TextBasePrompt.parseBubbleDuration("0"));
        assertEquals(Duration.ofMillis(Integer.MAX_VALUE * 50L), TextBasePrompt.parseBubbleDuration(Integer.MAX_VALUE + "t"));
    }

    @Test void invalidBubbleDurationDoesNotWrapOrBecomeNegative() {
        for (String input : new String[] {"", " ", "NaN", "-1", "-1t", "PT-1S", "999999999999999d", "2147483648t", "2000000000s"})
            assertThrows(CommandException.class, () -> TextBasePrompt.parseBubbleDuration(input));
        assertThrows(CommandException.class, () -> TextBasePrompt.parseBubbleDuration(null));
    }
}
