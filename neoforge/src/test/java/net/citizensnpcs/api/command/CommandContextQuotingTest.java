package net.citizensnpcs.api.command;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CommandContextQuotingTest {
    @Test void emptyQuotedFlagValueDoesNotConsumeTheFollowingFlag() {
        var context = new CommandContext(new String[] {"npc", "textdisplay", "--text", "\"\"", "--shadowed", "false"});
        assertEquals("", context.getFlag("text"));
        assertEquals("false", context.getFlag("shadowed"));
        assertEquals(1, context.argsLength());
        var last = new CommandContext(new String[] {"npc", "bossbar", "--flags", "''"});
        assertTrue(last.hasValueFlag("flags"));
        assertEquals("", last.getFlag("flags"));
        assertEquals(1, last.argsLength());
    }

    @Test void quotedPositionalValuesRetainIntentionalWhitespaceAndEmptiness() {
        var empty = new CommandContext(new String[] {"npc", "create", "\"\""});
        assertEquals(2, empty.argsLength());
        assertEquals("", empty.getString(1));
        var spaced = new CommandContext(new String[] {"npc", "rename", "\"", "one", "\""});
        assertEquals(" one ", spaced.getString(1));
        assertEquals(2, spaced.argsLength());
    }
}
