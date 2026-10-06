package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.trait.waypoint.triggers.*;

class TriggerAddInputTest {
    private static MemoryDataKey create(String input) {
        var prompt = new TriggerAddPrompt();
        var session = new ChatPromptSession(null, prompt, null);
        assertNull(prompt.acceptInput(session, input));
        var created = assertInstanceOf(WaypointTrigger.class, session.getSessionData(WaypointTriggerPrompt.CREATED_TRIGGER_KEY));
        var saved = new MemoryDataKey();
        new WaypointTriggerRegistry().save(created, saved);
        return saved;
    }

    @Test void inlineCommandsKeepCaseSensitiveArgumentsAndInternalSpacing() {
        String command = "scoreboard players set ExactPlayer  CaseSensitiveObjective 1";
        var saved = create("CoMmAnD\t" + command);
        assertEquals("command", saved.getString("type"));
        assertEquals(command, saved.getString("commands.0"));
    }

    @Test void inlineChatPreservesOriginalText() {
        String message = "Hello, I am Alice  McGee!";
        var saved = create("CHAT " + message);
        assertEquals("chat", saved.getString("type"));
        assertEquals(message, saved.getString("lines.0"));
    }

    @Test void triggerTypeMatchingDoesNotDependOnSystemLanguage() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertInstanceOf(AnimationTriggerPrompt.class, WaypointTriggerRegistry.getTriggerPromptFrom("ANIMATION"));
            assertEquals("Istanbul I", create("CHAT Istanbul I").getString("lines.0"));
        } finally { Locale.setDefault(previous); }
    }
}
