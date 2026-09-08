package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the matching rule {@link ParadigmGroups} applies to what Paradigm's API hands back.
 * <p>
 * The reflection itself cannot be tested without Paradigm on the classpath, but the decision made from its answer can,
 * and that is where the behaviour lives: a guard attacks on a match, so case handling and the primary-group special
 * case have to be right.
 */
public class ParadigmGroupsTest {
    @Test
    public void aResolvedGroupMatches() {
        assertTrue(ParadigmGroups.matches(List.of("default", "udays_crime"), "default", "udays_crime"));
        assertFalse(ParadigmGroups.matches(List.of("default"), "default", "udays_crime"));
    }

    @Test
    public void thePrimaryGroupMatchesEvenIfNotListedAsResolved() {
        assertTrue(ParadigmGroups.matches(List.of(), "udays_crime", "udays_crime"));
    }

    @Test
    public void matchingIsCaseAndPaddingInsensitive() {
        // Sentinel's stored rule and the group as configured will not agree on case forever
        assertTrue(ParadigmGroups.matches(List.of("UDays_Crime"), "default", "udays_crime"));
        assertTrue(ParadigmGroups.matches(List.of("  udays_crime  "), "default", "UDAYS_CRIME"));
    }

    @Test
    public void aMissingAnswerIsNotAMatch() {
        assertFalse(ParadigmGroups.matches(null, null, "udays_crime"));
        assertFalse(ParadigmGroups.matches(List.of(), null, "udays_crime"));
    }

    @Test
    public void aNullEntryInTheListIsSkipped() {
        assertTrue(ParadigmGroups.matches(Arrays.asList(null, "udays_crime"), null, "udays_crime"));
    }
}
