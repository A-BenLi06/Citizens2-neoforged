package net.citizensnpcs.trait.waypoint.triggers;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class NumericTriggerInputTest {
    @Test void delayRejectsMalformedAndOverflowingValuesInsteadOfCreatingZero() {
        var prompt = new DelayTriggerPrompt();
        for (String input : new String[] { null, "", " ", "wrong", "NaN", "Infinity", "2147483648t",
                "107374182.4s", "999999999999999999h" })
            assertNull(prompt.createFromShortInput(null, input), String.valueOf(input));
    }

    @Test void delayPreservesTickDurationAndFiniteNegativeCompatibility() {
        var prompt = new DelayTriggerPrompt();
        assertEquals(20, ((DelayTrigger) prompt.createFromShortInput(null, "20")).getDelay());
        assertEquals(60, ((DelayTrigger) prompt.createFromShortInput(null, "3s")).getDelay());
        assertEquals(1, ((DelayTrigger) prompt.createFromShortInput(null, "0.075s")).getDelay());
        assertEquals(0, ((DelayTrigger) prompt.createFromShortInput(null, "-3s")).getDelay());
        assertEquals(Integer.MAX_VALUE, ((DelayTrigger) prompt.createFromShortInput(null, "2147483647")).getDelay());
    }

    @Test void speedRejectsNonFiniteAndUnrepresentableValues() {
        var prompt = new SpeedTriggerPrompt();
        for (String input : new String[] { null, "", "wrong", "NaN", "Infinity", "-Infinity", "1e309", "1e39" })
            assertNull(prompt.createFromShortInput(null, input), String.valueOf(input));
        assertEquals(1.25F, ((SpeedTrigger) prompt.createFromShortInput(null, "1.25")).getSpeed());
        assertEquals(0F, ((SpeedTrigger) prompt.createFromShortInput(null, "-3")).getSpeed());
        assertEquals(Float.MAX_VALUE, ((SpeedTrigger) prompt.createFromShortInput(null, Float.toString(Float.MAX_VALUE))).getSpeed());
    }
}
