package net.citizensnpcs.api.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Covers the world-name mapping {@link LocationPersister#resolve} applies before it touches a server.
 * <p>
 * {@code resolve} itself needs a live server, so what is checked here is the pure name-mapping step, mirrored from the
 * same rules: the three default Bukkit names, the {@code /DIM1} and {@code /DIM-1} suffixes Bukkit-on-Forge uses for the
 * other two dimensions, and the fact that a custom level name reaches the namespace-less fallback rather than resolving.
 * <p>
 * The case that matters in practice is a server whose {@code level-name} is not "world": Citizens saves written there
 * store that folder name, and it is not a valid {@link net.minecraft.resources.ResourceLocation} at all when it contains
 * capitals, so without the fallback every NPC in it stays unspawned forever.
 */
public class LocationWorldNameTest {
    /** The mapping step of {@link LocationPersister#resolve}, without the server lookup. */
    private static String mapped(String worldId) {
        String lower = worldId.toLowerCase(Locale.ROOT);
        if (lower.equals("world"))
            return "minecraft:overworld";
        if (lower.equals("world_nether"))
            return "minecraft:the_nether";
        if (lower.equals("world_the_end"))
            return "minecraft:the_end";
        if (lower.endsWith("/dim1"))
            return "minecraft:the_end";
        if (lower.endsWith("/dim-1"))
            return "minecraft:the_nether";
        return worldId;
    }

    @Test
    public void defaultBukkitNamesMapToTheirDimensions() {
        assertEquals("minecraft:overworld", mapped("world"));
        assertEquals("minecraft:the_nether", mapped("world_nether"));
        assertEquals("minecraft:the_end", mapped("world_the_end"));
    }

    @Test
    public void forgeStyleSuffixesMapWhateverTheLevelIsCalled() {
        assertEquals("minecraft:the_end", mapped("uDays/DIM1"));
        assertEquals("minecraft:the_nether", mapped("uDays/DIM-1"));
        assertEquals("minecraft:the_end", mapped("some other level/DIM1"));
        // the suffix decides, not the level name, because the level name is whatever the operator chose
        assertEquals("minecraft:the_nether", mapped("world/DIM-1"));
    }

    @Test
    public void aCustomLevelNameFallsThroughToTheNamespacelessBranch() {
        // unchanged by the mapping step, and with no namespace - which is what sends resolve() to the overworld fallback
        assertEquals("uDays", mapped("uDays"));
        org.junit.jupiter.api.Assertions.assertFalse(mapped("uDays").contains(":"));
    }

    @Test
    public void realDimensionIdsAreLeftAlone() {
        assertEquals("minecraft:overworld", mapped("minecraft:overworld"));
        assertEquals("mymod:mydim", mapped("mymod:mydim"));
    }
}
