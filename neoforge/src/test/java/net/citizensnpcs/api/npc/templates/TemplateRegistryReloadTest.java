package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.resources.ResourceLocation;

class TemplateRegistryReloadTest {
    @Test void collectionQueriesAreOrderedByFullyQualifiedKey(@TempDir Path folder) throws Exception {
        Path z = Files.createDirectories(folder.resolve("zeta"));
        Path a = Files.createDirectories(folder.resolve("alpha"));
        Files.writeString(z.resolve("templates.yml"), "shared: {}\nfirst: {}\n");
        Files.writeString(a.resolve("templates.yml"), "shared: {}\nlast: {}\n");
        var registry = TemplateRegistry.prepareReload(folder);
        assertEquals(java.util.List.of("alpha:last", "alpha:shared", "zeta:first", "zeta:shared"),
                registry.getAllTemplates().stream().map(template -> template.getKey().toString()).toList());
        assertEquals(java.util.List.of("alpha:shared", "zeta:shared"),
                registry.getTemplates("shared").stream().map(template -> template.getKey().toString()).toList());
    }

    @Test void emptyTemplateNamesAreRejected(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Path file = Files.writeString(namespace.resolve("empty-name.yml"), "'': {}\n");
        var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
        assertTrue(failure.getCause().getMessage().contains("not a valid key"));
        assertEquals("'': {}\n", Files.readString(file));
    }

    @Test void scalarTemplateEntriesCannotBecomeSuccessfulNoOps(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Path file = namespace.resolve("bad.yml");
        for (String value : java.util.List.of("plain-text", "42", "true", "null")) {
            Files.writeString(file, "bad: " + value + "\n");
            var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
            assertTrue(failure.getCause().getMessage().contains("audit:bad: Expected template mapping"));
        }
    }

    @Test void explicitEmptyMappingIsAValidTemplate(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Files.writeString(namespace.resolve("empty.yml"), "empty: {}\n");
        assertNotNull(TemplateRegistry.prepareReload(folder)
                .getTemplateByKey(ResourceLocation.fromNamespaceAndPath("audit", "empty")));
    }

    @Test void namespaceQueriesUseTheSameLocaleIndependentNormalizationAsLoading(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("Mixed"));
        Files.writeString(namespace.resolve("templates.yml"), "Item: {}\n");
        var registry = TemplateRegistry.prepareReload(folder);
        var previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertTrue(registry.hasNamespace("MIXED"));
            assertTrue(registry.hasNamespace("mixed"));
            assertEquals(1, registry.getTemplates("ITEM").size());
            assertFalse(registry.hasNamespace("missing"));
            assertFalse(registry.hasNamespace(null));
        } finally { java.util.Locale.setDefault(previous); }
    }

    @Test void runtimeParserFailureRetainsTemplateIdentityAndOriginalCause(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Files.writeString(namespace.resolve("runtime.yml"), "requires_runtime:\n  traits:\n    - name: behavior\n");
        // This file needs a live trait factory; the unit environment deliberately has no enabled Citizens instance.
        var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
        assertTrue(failure.getCause().getMessage().contains("audit:requires_runtime"));
        assertInstanceOf(IllegalStateException.class, failure.getCause().getCause());
        assertTrue(failure.getCause().getCause().getMessage().contains("Citizens is not enabled"));
    }

    @Test void malformedCommandShapesCannotBecomeEmptyActions(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Path file = namespace.resolve("bad.yml");
        String[] bodies = { "  commands: say hello\n", "  commands:\n    on_spawn: say hello\n",
                "  commands:\n    on_spawn:\n      message: say hello\n",
                "  commands:\n    on_spawn:\n      - command: say hello\n" };
        for (String body : bodies) {
            Files.writeString(file, "bad:\n" + body);
            var failure = assertThrows(IllegalStateException.class, () -> TemplateRegistry.prepareReload(folder));
            assertTrue(failure.getCause().getMessage().toLowerCase().contains("command"));
        }
    }

    @Test void explicitlyEmptyCommandListsRemainValid(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Files.writeString(namespace.resolve("empty.yml"), "empty:\n  commands:\n    on_spawn: []\n");
        assertEquals(1, TemplateRegistry.prepareReload(folder).getAllTemplates().size());
    }

    @Test void directListDataCanBeUsedWithoutYamlNormalization() {
        var key = new net.citizensnpcs.api.util.MemoryDataKey();
        key.setRaw("commands", java.util.Map.of("on_spawn", java.util.List.of("say hello")));
        var errors = new TemplateRegistry.TemplateErrorReporter() {
            @Override public void addError(String message) { fail(message); }
        };
        assertNotNull(Template.load(errors, null, ResourceLocation.fromNamespaceAndPath("audit", "direct"), key));
    }

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
