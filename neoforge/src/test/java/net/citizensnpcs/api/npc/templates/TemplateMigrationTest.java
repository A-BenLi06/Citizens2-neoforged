package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.resources.ResourceLocation;

class TemplateMigrationTest {
    @Test void invalidLegacyOptionsAreRejectedBeforeFilesystemChanges(@TempDir Path root) throws Exception {
        int index = 0;
        for (String yaml : java.util.List.of("override: perhaps\nreplacements: {}\n", "replacements: wrong-type\n")) {
            Path folder = Files.createDirectory(root.resolve("invalid" + index++));
            Path source = Files.writeString(folder.resolve("sample.yml"), yaml);
            var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
            assertTrue(failure.getCause().getMessage().contains("Invalid legacy template contents"));
            assertEquals(yaml, Files.readString(source));
            assertFalse(Files.exists(folder.resolve("migrated")));
            assertFalse(Files.exists(folder.resolve("sample.yml.migrated")));
        }
    }

    @Test void migrationUsesTheSameQuotedBooleanNormalizationAsReplacementLoading(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("sample.yml"), "override: ' true '\nreplacements:\n  name: Original\n");
        TemplateRegistry.prepareReload(folder);
        var saved = new net.citizensnpcs.api.util.YamlStorage(folder.resolve("migrated/sample.yml").toFile());
        assertTrue(saved.load());
        assertEquals(Boolean.TRUE, saved.getKey("").getRaw("sample.yaml_replace.override"));
    }

    @Test void invalidNamesAreRejectedBeforeWritingOrArchiving(@TempDir Path root) throws Exception {
        int index = 0;
        for (String name : java.util.List.of("bad name.yml", ".yml")) {
            Path folder = Files.createDirectory(root.resolve("case" + index++));
            Path source = Files.writeString(folder.resolve(name), SOURCE);
            var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
            assertTrue(failure.getCause().getMessage().contains("Invalid legacy template name"));
            assertEquals(SOURCE, Files.readString(source));
            assertFalse(Files.exists(folder.resolve("migrated")));
            assertFalse(Files.exists(folder.resolve(name + ".migrated")));
        }
    }

    private static final String SOURCE = "override: true\nreplacements:\n  name: Original\n";

    @Test void conflictingDestinationPreservesBothFiles(@TempDir Path folder) throws Exception {
        Path source = Files.writeString(folder.resolve("sample.yml"), SOURCE);
        Path namespace = Files.createDirectory(folder.resolve("migrated"));
        Path target = Files.writeString(namespace.resolve("sample.yml"), "sample:\n  yaml_replace:\n    replacements:\n      name: Edited\n");
        byte[] before = Files.readAllBytes(target);
        assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
        assertEquals(SOURCE, Files.readString(source)); assertArrayEquals(before, Files.readAllBytes(target));
        assertFalse(Files.exists(folder.resolve("sample.yml.migrated")));
    }

    @Test void identicalDestinationCompletesRetryWithoutRewritingIt(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("sample.yml"), SOURCE);
        Path namespace = Files.createDirectory(folder.resolve("migrated"));
        Path target = Files.writeString(namespace.resolve("sample.yml"), "# keep formatting\nsample:\n  yaml_replace:\n    override: true\n    replacements:\n      name: Original\n");
        byte[] before = Files.readAllBytes(target);
        var registry = TemplateRegistry.prepareReload(folder);
        assertNotNull(registry.getTemplateByKey(ResourceLocation.fromNamespaceAndPath("migrated", "sample")));
        assertArrayEquals(before, Files.readAllBytes(target));
        assertEquals(SOURCE, Files.readString(folder.resolve("sample.yml.migrated")));
        assertFalse(Files.exists(folder.resolve("sample.yml")));
    }

    @Test void existingArchiveIsNeverReplaced(@TempDir Path folder) throws Exception {
        Path source = Files.writeString(folder.resolve("sample.yml"), SOURCE);
        Path archive = Files.writeString(folder.resolve("sample.yml.migrated"), "previous archive");
        assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
        assertEquals(SOURCE, Files.readString(source)); assertEquals("previous archive", Files.readString(archive));
        assertFalse(Files.exists(folder.resolve("migrated/sample.yml")));
    }

    @Test void migrationPreservesDotsAndOnlyRemovesTheFinalExtension(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("name.yml.part.yml"), SOURCE);
        var registry = TemplateRegistry.prepareReload(folder);
        assertNotNull(registry.getTemplateByKey(ResourceLocation.fromNamespaceAndPath("migrated", "name.yml.part")));
        assertEquals(1, registry.getAllTemplates().size());
    }
}
