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

    @Test void explicitUuidTargetsDoNotGainAConflictingId() {
        String uuid = "28dfd633-8f97-42f1-a05d-2c3d978bcad3";
        for (String command : new String[] { "npc select", "npc equip", "waypoints list", "wp list" }) {
            String explicit = command + " --uuid " + uuid + " --registry visitors";
            String prepared = NPCCommandFormat.prepare(" /" + explicit + " ");
            assertEquals(explicit, prepared);
            var context = new CommandContext(prepared.split(" "));
            assertEquals(uuid, context.getValueFlags().get("uuid"));
            assertFalse(context.hasValueFlag("id"));
        }
    }

    @Test void quotedUuidTextStillReceivesAnAutomaticTarget() {
        assertEquals("npc speak \"literal --uuid value\" --id <id>",
                NPCCommandFormat.prepare("npc speak \"literal --uuid value\""));
    }
}
