package net.citizensnpcs.trait.waypoint.triggers;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TeleportTriggerInputTest {
    @Test void nativeDimensionIdIsPreservedBeforeTheLastThreeCoordinates() {
        assertEquals(new TeleportTriggerPrompt.Destination("minecraft:the_nether", 4, 70, -4),
                TeleportTriggerPrompt.parseDestination("minecraft:the_nether:4:70:-4"));
        assertEquals(new TeleportTriggerPrompt.Destination("mod:deep/caves", -12, -64, 30),
                TeleportTriggerPrompt.parseDestination("mod:deep/caves:-12:-64:30"));
    }

    @Test void bareWorldNamesAndWhitespaceRemainSupported() {
        assertEquals(new TeleportTriggerPrompt.Destination("world", 1, 2, 3),
                TeleportTriggerPrompt.parseDestination(" world : 1 : 2 : 3 "));
    }

    @Test void malformedAndOutOfRangeCoordinatesAreRejected() {
        for (String input : new String[] { null, "", "world", "world:1:2", ":1:2:3", "world::2:3",
                "world:1:2:", "world:NaN:2:3", "world:1.5:2:3", "world:2147483648:2:3" })
            assertThrows(IllegalArgumentException.class, () -> TeleportTriggerPrompt.parseDestination(input));
    }
}
