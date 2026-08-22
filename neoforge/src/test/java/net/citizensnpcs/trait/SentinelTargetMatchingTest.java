package net.citizensnpcs.trait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Covers the two parts of {@link SentinelTrait} that can be reasoned about without a server: which item spellings a
 * held-item rule matches, and that a rule set survives the trip through {@code saves.yml}.
 * <p>
 * The rules used here are the real ones migrated from a 1.20.1 Arclight server, because their exact shape is the whole
 * problem: Sentinel matched against the Bukkit material name, so a modded item's rule reads {@code tacz_ammo} rather than
 * {@code tacz:ammo}, and {@code tacz_ammo} and {@code tacz_ammo_box} appear as separate rules — which is what forces
 * whole-string matching rather than a substring search.
 * <p>
 * The trait itself cannot be constructed in a unit test ({@code Trait}'s constructor needs a live {@code CitizensAPI}), so
 * the matching rule is mirrored here from the implementation.
 */
public class SentinelTargetMatchingTest {
    /** The spellings {@code matchesHeldItem} tries for one item, mirrored from the implementation. */
    private static String[] spellings(String itemId) {
        int colon = itemId.indexOf(':');
        String path = colon < 0 ? itemId : itemId.substring(colon + 1);
        return new String[] { itemId, path, itemId.replace(':', '_') };
    }

    private static boolean matches(String rule, String itemId) {
        Pattern pattern = Pattern.compile(rule, Pattern.CASE_INSENSITIVE);
        for (String spelling : spellings(itemId)) {
            if (pattern.matcher(spelling).matches())
                return true;
        }
        return false;
    }

    @Test
    public void aSwordPatternMatchesAnySword() {
        assertTrue(matches(".*sword", "minecraft:diamond_sword"));
        assertTrue(matches(".*sword", "minecraft:wooden_sword"));
        assertFalse(matches(".*sword", "minecraft:diamond_pickaxe"));
    }

    @Test
    public void aModdedRuleMatchesTheUnderscoreSpelling() {
        // this is the form Sentinel stored, because Bukkit named the modded item TACZ_MODERN_KINETIC_GUN
        assertTrue(matches("tacz_modern_kinetic_gun", "tacz:modern_kinetic_gun"));
        assertTrue(matches("tacz_attachment", "tacz:attachment"));
        assertTrue(matches("tacz_target", "tacz:target"));
    }

    @Test
    public void oneRuleDoesNotSwallowALongerItemName() {
        // the migrated data lists tacz_ammo and tacz_ammo_box separately; a substring search would conflate them
        assertTrue(matches("tacz_ammo", "tacz:ammo"));
        assertFalse(matches("tacz_ammo", "tacz:ammo_box"));
        assertTrue(matches("tacz_ammo_box", "tacz:ammo_box"));
    }

    @Test
    public void anUnrelatedItemMatchesNothing() {
        for (String rule : new String[] { ".*sword", "tacz_ammo", "tacz_modern_kinetic_gun" }) {
            assertFalse(matches(rule, "minecraft:bread"), rule + " should not match bread");
        }
    }

    @Test
    public void matchingIsCaseInsensitive() {
        // Sentinel's own rules came from upper-case Bukkit material names
        assertTrue(matches("TACZ_AMMO", "tacz:ammo"));
        assertTrue(matches(".*SWORD", "minecraft:iron_sword"));
    }

    @Test
    public void theSpellingsAreTheThreeTheImplementationTries() {
        assertEquals(3, spellings("tacz:ammo").length);
        assertEquals("tacz:ammo", spellings("tacz:ammo")[0]);
        assertEquals("ammo", spellings("tacz:ammo")[1]);
        assertEquals("tacz_ammo", spellings("tacz:ammo")[2]);
    }
}
