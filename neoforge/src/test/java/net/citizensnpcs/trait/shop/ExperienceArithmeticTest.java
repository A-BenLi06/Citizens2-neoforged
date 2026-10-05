package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExperienceArithmeticTest {
    @Test void multiplicationCannotBecomeFreeOrNegative() {
        assertEquals(6, ExperienceAction.totalLevels(2, 3));
        assertEquals(0, ExperienceAction.totalLevels(0, Integer.MAX_VALUE));
        assertEquals(0, ExperienceAction.totalLevels(10, 0));
        assertEquals(-1, ExperienceAction.totalLevels(-1, 1));
        assertEquals(-1, ExperienceAction.totalLevels(1, -1));
        assertEquals(-1, ExperienceAction.totalLevels(1 << 30, 4));
        assertEquals(-1, ExperienceAction.totalLevels(Integer.MAX_VALUE, 2));
        assertEquals(Integer.MAX_VALUE, ExperienceAction.totalLevels(Integer.MAX_VALUE, 1));
    }

    @Test void changesRespectBothNativeIntegerBounds() {
        assertTrue(ExperienceAction.canChangeLevel(10, -10));
        assertTrue(ExperienceAction.canChangeLevel(Integer.MAX_VALUE - 1, 1));
        assertFalse(ExperienceAction.canChangeLevel(10, -11));
        assertFalse(ExperienceAction.canChangeLevel(Integer.MAX_VALUE, 1));
        assertFalse(ExperienceAction.canChangeLevel(-1, 2));
    }

    @Test void repeatCountsDistinguishInvalidAndFreeCosts() {
        assertEquals(3, ExperienceAction.affordableRepeats(10, 3));
        assertEquals(-1, ExperienceAction.affordableRepeats(10, 0));
        assertEquals(0, ExperienceAction.affordableRepeats(10, -1));
        assertEquals(0, ExperienceAction.affordableRepeats(-1, 1));
        assertEquals(Integer.MAX_VALUE, ExperienceAction.affordableRepeats(Integer.MAX_VALUE, 1));
    }
}
