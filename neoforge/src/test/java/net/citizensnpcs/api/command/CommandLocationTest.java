package net.citizensnpcs.api.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CommandLocationTest {
    @Test void nativeNamespaceAndRotationsRemainDistinct() {
        assertEquals(new CommandLocation(1.5, -64, -2.25, 90, -30, "example:deep/caves"),
                CommandLocation.parse("1.5,-64,-2.25,example:deep/caves,90,-30"));
        assertEquals(new CommandLocation(1, 2, 3, 0, 0, "mod:123"), CommandLocation.parse("1,2,3,mod:123"));
    }

    @Test void optionalWorldAndAnglesHaveDocumentedDefaults() {
        assertEquals(new CommandLocation(1, 2, 3, 0, 0, null), CommandLocation.parse("1,2,3"));
        assertEquals(new CommandLocation(1, 2, 3, 0, 0, "world"), CommandLocation.parse("1,2,3,world"));
        assertEquals(new CommandLocation(1, 2, 3, 45, 0, "world"), CommandLocation.parse("1,2,3,world,45"));
    }

    @Test void denizenUsesWorldLastAndPreservesNames() {
        assertEquals(new CommandLocation(1, 2, 3, 45, -15, "example:moon"),
                CommandLocation.parse("l@1,2,3,45,-15,w@example:moon"));
        assertEquals(new CommandLocation(1, 2, 3, 45, -15, null), CommandLocation.parse("l@1,2,3,45,-15"));
        assertEquals(new CommandLocation(1, 2, 3, 0, 0, "My World"), CommandLocation.parse("l@1,2,3,w@My World"));
    }

    @Test void legacyColonCoordinatesAndScientificNumbersRemainAvailable() {
        assertEquals(new CommandLocation(1, 2, 3, 45, -15, "world"), CommandLocation.parse("1:2:3:world:45:-15"));
        assertEquals(new CommandLocation(100, -2, 0.3, 0, 0, null), CommandLocation.parse(" 1e2, -2.0, 3e-1 "));
    }

    @Test void malformedAndEmptyFieldsAreNeverCollapsed() {
        for (String input : new String[] { null, "", "1", "1,2", "1,,3", ",1,2,3", "1,2,3,", "1,2,3,w@",
                "1,2,3,world,,45", "1,2,3,world,0,0,extra", "1,nope,3", "1:2,3:world" })
            assertThrows(IllegalArgumentException.class, () -> CommandLocation.parse(input), String.valueOf(input));
    }

    @Test void nonFiniteCoordinatesAndAnglesAreRejected() {
        for (String input : new String[] { "NaN,2,3", "1,Infinity,3", "1,2,-Infinity", "1e309,2,3",
                "1,2,3,world,NaN", "1,2,3,world,1e99", "1,2,3,world,0,Infinity", "l@1,2,3,NaN,0" })
            assertThrows(IllegalArgumentException.class, () -> CommandLocation.parse(input), input);
    }
}
