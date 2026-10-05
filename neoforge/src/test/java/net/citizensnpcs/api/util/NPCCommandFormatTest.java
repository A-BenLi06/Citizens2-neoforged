package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.command.CommandContext;

class NPCCommandFormatTest {
    @Test void onlyTheWholeSayCommandIsRewritten() {
        assertEquals("sayhello message", NPCCommandFormat.prepare("sayhello message"));
        assertEquals("npc speak \"hello\" --target <p> --id <id>", NPCCommandFormat.prepare("/say hello"));
    }

    @Test void spokenQuotesAndBackslashesRoundTripAsOneArgument() {
        String message = "A \"quoted\" path C:\\folder";
        var context = new CommandContext(NPCCommandFormat.prepare("say " + message).split(" "));
        assertEquals("speak", context.getString(0));
        assertEquals(message, context.getString(1));
        assertEquals("<id>", context.getValueFlags().get("id"));
    }

    @Test void quotedIdTextDoesNotSuppressTargetSelection() {
        assertEquals("npc speak \"literal --id 5\" --id <id>",
                NPCCommandFormat.prepare("npc speak \"literal --id 5\""));
        assertEquals("npc equip --id 5", NPCCommandFormat.prepare("npc equip --id 5"));
    }

    @Test void prefixedNpcAliasesReceiveTheTarget() {
        assertEquals("npc equip --id <id>", NPCCommandFormat.prepare(" /npc equip "));
        assertEquals("wp list --id <id>", NPCCommandFormat.prepare("/wp list"));
        assertEquals("waypoints list --id <id>", NPCCommandFormat.prepare("waypoints list"));
    }
}
