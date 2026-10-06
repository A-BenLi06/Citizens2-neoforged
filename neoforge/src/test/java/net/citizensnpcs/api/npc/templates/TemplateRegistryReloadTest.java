package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.resources.ResourceLocation;

class TemplateRegistryReloadTest {
    @Test void misspelledCommandEventsRejectTheCandidate(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Files.writeString(namespace.resolve("bad.yml"), "bad:\n  commands:\n    on_spwan:\n      - say hello\n");
        var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
        assertTrue(failure.getCause().getMessage().contains("Unknown template command event on_spwan"));
    }

    @Test void supportedCommandEventsCanAllBePreparedWithoutRegisteringThem(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        StringBuilder yaml = new StringBuilder("valid:\n  commands:\n");
        for (String event : java.util.List.of("on_spawn", "on_death", "on_damage", "on_left_click", "on_right_click", "on_template_apply"))
            yaml.append("    ").append(event).append(":\n      - say hello\n");
        Files.writeString(namespace.resolve("valid.yml"), yaml);
        assertEquals(1, TemplateRegistry.prepareReload(folder).getAllTemplates().size());
    }

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
