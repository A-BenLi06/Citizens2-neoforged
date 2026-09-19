package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import net.minecraft.resources.ResourceLocation;

@ResourceLock(Resources.LOCALE)
class WorldsTest {
    private static final ResourceLocation OVERWORLD = id("minecraft:overworld");
    private static final ResourceLocation NETHER = id("minecraft:the_nether");
    private static final ResourceLocation END = id("minecraft:the_end");
    private static final ResourceLocation ISLAND = id("sky:island");
    private static final Set<ResourceLocation> VANILLA = Set.of(OVERWORLD, NETHER, END);
    private static final Set<ResourceLocation> LOADED = Set.of(OVERWORLD, NETHER, END, ISLAND);

    @TempDir Path folder;

    @BeforeEach void resetAliases() { Worlds.clear(); }
    @AfterEach void clearAliases() { Worlds.clear(); }

    @Test void namespacedRequestsRequireTheExactLoadedDimension() {
        assertEquals(ISLAND, resolve("sky:island", "uDays", LOADED));
        assertEquals(ISLAND, resolve("SKY:ISLAND", "uDays", LOADED));
        assertEquals(NETHER, resolve("MINECRAFT:THE_NETHER", "uDays", LOADED));
        for (String request : List.of("other:island", "minecraft:missing", "sky:", ":island", ":overworld", "sky:island:extra"))
            assertNull(resolve(request, "uDays", LOADED), request);
        assertNull(resolve("minecraft:overworld", "uDays", Set.of(ISLAND)));
        assertNull(resolve(null, "uDays", LOADED));
    }

    @Test void automaticLegacyNamesUseTheActualWorldNameAndLoadedDimensions() {
        assertEquals(OVERWORLD, resolve("uDays", "uDays", LOADED));
        assertEquals(OVERWORLD, resolve("UDAYS", "uDays", LOADED));
        for (String request : List.of("uDays/DIM-1", "uDays_nether"))
            assertEquals(NETHER, resolve(request, "uDays", LOADED), request);
        for (String request : List.of("uDays/DIM1", "uDays_the_end"))
            assertEquals(END, resolve(request, "uDays", LOADED), request);
        assertEquals(ISLAND, resolve("uDays/sky/island", "uDays", LOADED));
        assertEquals(ISLAND, resolve("UDAYS/SKY/ISLAND", "uDays", LOADED));
        assertEquals(ISLAND, resolve("uDays_sky_island", "uDays", LOADED));
        assertEquals(ISLAND, resolve("island", "uDays", LOADED));
        assertEquals(OVERWORLD, resolve("overworld", "uDays", LOADED));
        assertEquals(NETHER, resolve("the_nether", "uDays", LOADED));
        assertEquals(END, resolve("the_end", "uDays", LOADED));
        assertNull(resolve("uDays/DIM1", "uDays", Set.of(OVERWORLD)));
        assertNull(resolve("uDays/sky/island", "uDays", VANILLA));
    }

    @Test void unresolvedNamesCannotFallBackToAnUnrelatedDimension() {
        for (String request : List.of("", " ", "missing", "world", "world_nether", "world_the_end",
                "old/DIM1", "old/DIM-1", "old_nether", "old_the_end", "DIM1", "DIM-1",
                "uDays\\DIM1", "uDays\\sky\\island", " uDays", "uDays ", " sky:island", "sky:island "))
            assertNull(resolve(request, "uDays", LOADED), request);
        assertEquals(OVERWORLD, resolve("world", "world", LOADED));
        assertEquals(NETHER, resolve("world_nether", "world", LOADED));
        assertEquals(END, resolve("world_the_end", "world", LOADED));
    }

    @Test void automaticNameCollisionsFailInsteadOfDependingOnIterationOrder() {
        ResourceLocation otherIsland = id("other:island");
        Set<ResourceLocation> duplicatePaths = Set.of(OVERWORLD, ISLAND, otherIsland);
        assertNull(resolve("island", "uDays", duplicatePaths));
        assertEquals(ISLAND, resolve("sky:island", "uDays", duplicatePaths));
        assertEquals(otherIsland, resolve("other:island", "uDays", duplicatePaths));
        assertEquals(ISLAND, resolve("uDays/sky/island", "uDays", duplicatePaths));

        assertNull(resolve("island", "island", Set.of(OVERWORLD, ISLAND)));
        assertNull(resolve("uDays/DIM1", "uDays", Set.of(END, id("other:udays/dim1"))));
        assertNull(resolve("uDays_nether", "uDays", Set.of(NETHER, id("other:udays_nether"))));
        assertNull(resolve("uDays/sky/island", "uDays", Set.of(ISLAND, id("other:udays/sky/island"))));
        assertEquals(OVERWORLD, resolve("overworld", "overworld", VANILLA),
                "Several automatic names for the same dimension are not ambiguous");
    }

    @Test void flattenedCustomNamesPreserveTheRootAndRejectCollisions() {
        ResourceLocation nested = id("sky:path/part"), flattened = id("sky:path_part");
        ResourceLocation otherNamespace = id("sky_path:part");
        assertEquals(nested, resolve("Old/Realm_sky_path_part", "Old/Realm", Set.of(nested)));
        assertNull(resolve("Old_Realm_sky_path_part", "Old/Realm", Set.of(nested)));
        assertNull(resolve("realm_sky_path_part", "Realm", Set.of(nested, flattened)));
        assertNull(resolve("realm_sky_path_part", "Realm", Set.of(flattened, otherNamespace)));
        assertEquals(nested, resolve("Realm/sky/path/part", "Realm", Set.of(nested, flattened)));
        assertEquals(nested, resolve("sky:path/part", "Realm", Set.of(nested, flattened)));
    }

    @Test void explicitAliasesOverrideAutomaticNamesWithoutFollowingChains() {
        Map<String, ResourceLocation> aliases = Map.of("udays", ISLAND, "udays/dim1", NETHER,
                "island", OVERWORLD, "retired", id("missing:dimension"));
        assertEquals(ISLAND, Worlds.resolveId("UDAYS", "uDays", LOADED, aliases));
        assertEquals(NETHER, Worlds.resolveId("uDays/DIM1", "uDays", LOADED, aliases));
        assertEquals(OVERWORLD, Worlds.resolveId("island", "uDays", LOADED, aliases));
        assertNull(Worlds.resolveId("retired", "uDays", LOADED, aliases));
        assertNull(Worlds.resolveId("uDays", "uDays", VANILLA, Map.of("udays", ISLAND)),
                "An unavailable explicit target must not fall through to the automatic overworld name");
        assertEquals(ISLAND, Worlds.resolveId("sky:island", "uDays", LOADED, aliases));
    }

    @Test void aliasesReadAsCanonicalTargetsAndPreserveSignificantWhitespace() throws Exception {
        Path file = yaml("valid.yml", "FormerRealm: minecraft:overworld\n伍德: sky:island\n"
                + "' old name ': minecraft:the_end\nretired: missing:dimension\n");
        Map<String, ResourceLocation> aliases = Worlds.readAliases(file.toFile());
        assertEquals(Map.of("formerrealm", OVERWORLD, "伍德", ISLAND, " old name ", END,
                "retired", id("missing:dimension")), aliases);
        assertEquals(OVERWORLD, Worlds.resolveId("FORMERREALM", "uDays", LOADED, aliases));
        assertEquals(ISLAND, Worlds.resolveId("伍德", "uDays", LOADED, aliases));
        assertEquals(END, Worlds.resolveId(" old name ", "uDays", LOADED, aliases));
        assertNull(Worlds.resolveId("old name", "uDays", LOADED, aliases));
        assertNull(Worlds.resolveId("retired", "uDays", LOADED, aliases));
        assertTrue(Worlds.readAliases(yaml("empty-map.yml", "{}\n").toFile()).isEmpty());
    }

    @Test void malformedAliasFilesAreRejectedWithoutChangingTheirBytes() throws Exception {
        List<String> invalid = List.of("", "null\n", "[]\n", "scalar\n", "17\n", "true\n",
                "name: null\n", "name: 17\n", "name: true\n", "name: [minecraft:overworld]\n",
                "name: {dimension: minecraft:overworld}\n", "7: minecraft:overworld\n",
                "true: minecraft:overworld\n", "null: minecraft:overworld\n",
                "'': minecraft:overworld\n", "'   ': minecraft:overworld\n",
                "'minecraft:overworld': sky:island\n", "name: overworld\n", "name: SKY:ISLAND\n",
                "name: ' sky:island'\n", "name: 'sky:island '\n", "name: 'sky:bad path'\n",
                "name: ':island'\n", "name: 'sky:island:extra'\n",
                "name: minecraft:overworld\nname: sky:island\n",
                "Name: minecraft:overworld\nNAME: sky:island\n",
                "a: b\nb: minecraft:overworld\n", "name: [unterminated\n");
        for (int i = 0; i < invalid.size(); i++) {
            String content = invalid.get(i);
            Path file = yaml("invalid-" + i + ".yml", content);
            assertThrows(Exception.class, () -> Worlds.readAliases(file.toFile()), content);
            assertEquals(content, Files.readString(file), content);
        }
    }

    @Test void worldNamesAndAliasesUseEnglishFoldingRegardlessOfDefaultLocale() throws Exception {
        Locale original = Locale.getDefault();
        Locale originalDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale originalFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(OVERWORLD, resolve("ISLAND", "Island", VANILLA));
            assertEquals(END, resolve("ISLAND/DIM1", "Island", VANILLA));
            assertEquals(NETHER, resolve("MINECRAFT:THE_NETHER", "Island", VANILLA));
            Map<String, ResourceLocation> aliases = Worlds.readAliases(
                    yaml("locale.yml", "ISTANBUL: minecraft:overworld\n").toFile());
            assertEquals(Map.of("istanbul", OVERWORLD), aliases);
            assertEquals(OVERWORLD, Worlds.resolveId("ISTANBUL", "uDays", VANILLA, aliases));
            Path duplicates = yaml("locale-duplicates.yml", "ISLAND: minecraft:overworld\nisland: sky:island\n");
            assertThrows(Exception.class, () -> Worlds.readAliases(duplicates.toFile()));
        } finally {
            Locale.setDefault(original);
            Locale.setDefault(Locale.Category.DISPLAY, originalDisplay);
            Locale.setDefault(Locale.Category.FORMAT, originalFormat);
        }
    }

    @Test void missingAliasFileCreatesAnEmptyConfiguration() throws Exception {
        Path file = folder.resolve("new/world-aliases.yml");
        assertFalse(Files.exists(file));
        assertTrue(Worlds.load(file.toFile()));
        assertTrue(Files.isRegularFile(file));
        assertTrue(Worlds.readAliases(file.toFile()).isEmpty());
        assertEquals(OVERWORLD, Worlds.resolveId("uDays", "uDays", LOADED));
        assertNull(Worlds.resolveId("FormerRealm", "uDays", LOADED));
    }

    @Test void failedReloadRetainsThePreviousSnapshotAndSuccessfulReloadReplacesIt() throws Exception {
        Path file = yaml("reload.yml", "FormerRealm: sky:island\n");
        assertTrue(Worlds.load(file.toFile()));
        assertEquals(ISLAND, Worlds.resolveId("FormerRealm", "uDays", LOADED));

        String malformed = "FormerRealm: 7\n";
        Files.writeString(file, malformed);
        assertFalse(Worlds.load(file.toFile()));
        assertEquals(malformed, Files.readString(file));
        assertEquals(ISLAND, Worlds.resolveId("FormerRealm", "uDays", LOADED));

        Files.writeString(file, "Replacement: minecraft:the_end\n");
        assertEquals(ISLAND, Worlds.resolveId("FormerRealm", "uDays", LOADED),
                "Editing a file cannot mutate the active snapshot before reload");
        assertNull(Worlds.resolveId("Replacement", "uDays", LOADED));
        assertTrue(Worlds.load(file.toFile()));
        assertNull(Worlds.resolveId("FormerRealm", "uDays", LOADED));
        assertEquals(END, Worlds.resolveId("Replacement", "uDays", LOADED));

        Files.writeString(file, "{}\n");
        assertTrue(Worlds.load(file.toFile()));
        assertNull(Worlds.resolveId("Replacement", "uDays", LOADED));
        assertEquals(OVERWORLD, Worlds.resolveId("uDays", "uDays", LOADED));
    }

    private Path yaml(String name, String content) throws Exception {
        Path file = folder.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static ResourceLocation resolve(String requested, String worldName, Set<ResourceLocation> loaded) {
        return Worlds.resolveId(requested, worldName, loaded, Map.of());
    }

    private static ResourceLocation id(String value) { return ResourceLocation.parse(value); }
}
