package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.YamlStorage;
import net.minecraft.resources.ResourceLocation;

class TemplateGenerationTest {
    private static NPC snapshot(Consumer<DataKey> save) {
        return (NPC) java.lang.reflect.Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] { NPC.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("saveSnapshot")) { save.accept((DataKey) args[0]); return null; }
                    throw new AssertionError("Unexpected NPC call: " + method.getName());
                });
    }

    @Test void overwriteKeepsBothIndexesAndSiblingTemplatesConsistent(@TempDir Path folder) {
        var registry = TemplateRegistry.prepareReload(folder);
        var first = ResourceLocation.fromNamespaceAndPath("audit", "first");
        var second = ResourceLocation.fromNamespaceAndPath("audit", "second");
        registry.generateTemplateFromNPC(first, snapshot(key -> key.setString("name", "Old")));
        registry.generateTemplateFromNPC(second, snapshot(key -> key.setString("name", "Sibling")));
        Template previous = registry.getTemplateByKey(first);
        Template sibling = registry.getTemplateByKey(second);
        registry.generateTemplateFromNPC(first, snapshot(key -> key.setString("name", "New")));
        assertNotSame(previous, registry.getTemplateByKey(first));
        assertEquals(java.util.List.of(registry.getTemplateByKey(first)), java.util.List.copyOf(registry.getTemplates("first")));
        assertSame(sibling, registry.getTemplateByKey(second));
        assertEquals(2, TemplateRegistry.prepareReload(folder).getAllTemplates().size());
    }

    @Test void preservesSourceFileAndLiteralKeyButDropsOldSnapshotFields(@TempDir Path folder) throws Exception {
        Path namespace = Files.createDirectories(folder.resolve("audit"));
        Path source = namespace.resolve("custom.yml");
        Files.writeString(source, "Mixed.Name:\n  yaml_replace:\n    replacements:\n      stale: old\n");
        var registry = TemplateRegistry.prepareReload(folder);
        registry.generateTemplateFromNPC(ResourceLocation.fromNamespaceAndPath("audit", "mixed.name"), snapshot(key -> {
            key.setString("name", "Fresh"); key.setString("uuid", "not copied");
        }));
        YamlStorage saved = new YamlStorage(source.toFile());
        assertTrue(saved.load());
        var entry = saved.getKey("").getSubKeys().iterator().next();
        assertEquals("Mixed.Name", entry.name());
        assertEquals("Fresh", entry.getString("yaml_replace.replacements.name"));
        assertFalse(entry.keyExists("yaml_replace.replacements.stale"));
        assertFalse(entry.keyExists("yaml_replace.replacements.uuid"));
        assertFalse(Files.exists(namespace.resolve("templates.yml")));
        assertEquals(1, TemplateRegistry.prepareReload(folder).getAllTemplates().size());
    }

    @Test void failedSnapshotPreservesFileAndBothIndexes(@TempDir Path folder) throws Exception {
        var registry = TemplateRegistry.prepareReload(folder);
        var key = ResourceLocation.fromNamespaceAndPath("audit", "saved");
        registry.generateTemplateFromNPC(key, snapshot(root -> root.setString("name", "Old")));
        Template previous = registry.getTemplateByKey(key);
        Path source = folder.resolve("audit/templates.yml");
        byte[] before = Files.readAllBytes(source);
        assertThrows(IllegalStateException.class, () -> registry.generateTemplateFromNPC(key, snapshot(root -> {
            root.setString("name", "Partial"); throw new IllegalStateException("save failure");
        })));
        assertArrayEquals(before, Files.readAllBytes(source));
        assertSame(previous, registry.getTemplateByKey(key));
        assertEquals(java.util.List.of(previous), java.util.List.copyOf(registry.getTemplates("saved")));
    }
}
