package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.resources.ResourceLocation;

class TemplateRegistryReloadTest {
    @Test void replacementTemplateLoadsThroughItsPersister(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Files.writeString(namespace.resolve("valid.yml"), "valid:\n  yaml_replace:\n    override: true\n    replacements:\n      name: Changed\n");
        var registry = TemplateRegistry.prepareReload(folder);
        assertNotNull(registry.getTemplateByKey(ResourceLocation.fromNamespaceAndPath("audit", "valid")));
        assertEquals(1, registry.getTemplates("valid").size());
    }

    @Test void malformedFileRejectsTheCandidate(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Files.writeString(namespace.resolve("bad.yml"), "broken: [unfinished\n");
        assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
    }

    @Test void duplicateKeysRejectTheCandidate(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Files.writeString(namespace.resolve("first.yml"), "duplicate: {}\n");
        Files.writeString(namespace.resolve("second.yml"), "duplicate: {}\n");
        var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
        assertTrue(failure.getCause().getMessage().contains("Duplicate template key audit:duplicate"));
    }

    @Test void fileCannotMasqueradeAsTemplateDirectory(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("templates");
        Files.writeString(file, "not a directory");
        assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(file));
    }
}
