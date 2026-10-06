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
    private static net.citizensnpcs.api.expr.Memory applyMemoryTemplate(Template template) {
        var result = new net.citizensnpcs.api.expr.Memory();
        var target = new java.util.concurrent.atomic.AtomicReference<NPC>();
        var registry = (net.citizensnpcs.api.npc.NPCRegistry) java.lang.reflect.Proxy.newProxyInstance(
                NPC.class.getClassLoader(), new Class<?>[]{net.citizensnpcs.api.npc.NPCRegistry.class},
                (proxy, method, args) -> target.get());
        var uuid = java.util.UUID.randomUUID();
        NPC npc = (NPC) java.lang.reflect.Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getOwningRegistry": return registry;
                        case "getId": return 1;
                        case "getUniqueId": return uuid;
                        case "isSpawned": return false;
                        case "saveSnapshot": return null;
                        case "load": result.loadFrom(((DataKey) args[0]).getRelative("traits.behavior.memory")); return null;
                        default: throw new AssertionError(method.getName());
                    }
                });
        target.set(npc); template.apply(npc);
        return result;
    }

    @Test void generatedAndReloadedTemplatesProduceEquivalentBlackboardState(@TempDir Path folder) {
        var registry = TemplateRegistry.prepareReload(folder);
        var key = ResourceLocation.fromNamespaceAndPath("audit", "memory");
        registry.generateTemplateFromNPC(key, snapshot(root -> {
            root.setRaw("traits.behavior.memory.values", java.util.Arrays.asList("first", null, "last"));
            root.setRaw("traits.behavior.memory.empty", java.util.List.of());
            root.setInt("traits.behavior.memory.counter", 7);
        }));
        var direct = applyMemoryTemplate(registry.getTemplateByKey(key));
        var reloaded = applyMemoryTemplate(TemplateRegistry.prepareReload(folder).getTemplateByKey(key));
        for (var memory : java.util.List.of(direct, reloaded)) {
            assertEquals(java.util.Arrays.asList("first", null, "last"), memory.getList("values"));
            assertTrue(memory.has("empty"));
            assertTrue(memory.getList("empty").isEmpty());
            assertEquals(7D, memory.getNumber("counter"));
        }
    }

    public static final class BrokenSerialization {
        int calls;
        public String getValue() { calls++; throw new IllegalStateException("injected serialization failure"); }
        public void setValue(String unused) { }
    }

    @Test void failedFileSerializationPreservesIndexesAndAllowsRetry(@TempDir Path folder) throws Exception {
        var registry = TemplateRegistry.prepareReload(folder);
        var key = ResourceLocation.fromNamespaceAndPath("audit", "saved");
        registry.generateTemplateFromNPC(key, snapshot(root -> root.setString("name", "Old")));
        Template previous = registry.getTemplateByKey(key);
        Path file = folder.resolve("audit/templates.yml");
        byte[] before = Files.readAllBytes(file);
        var broken = new BrokenSerialization();
        assertThrows(RuntimeException.class, () -> registry.generateTemplateFromNPC(key, snapshot(root -> root.setRaw("broken", broken))));
        assertTrue(broken.calls > 0, "Failure must reach the YAML writer");
        assertArrayEquals(before, Files.readAllBytes(file));
        assertSame(previous, registry.getTemplateByKey(key));
        assertEquals(java.util.List.of(previous), java.util.List.copyOf(registry.getTemplates("saved")));
        registry.generateTemplateFromNPC(key, snapshot(root -> root.setString("name", "Recovered")));
        assertNotSame(previous, registry.getTemplateByKey(key));
        var saved = new YamlStorage(file.toFile()); assertTrue(saved.load());
        assertEquals("Recovered", saved.getKey("").getString("saved.yaml_replace.replacements.name"));
    }

    @Test void emptyNameGenerationFailsBeforeSnapshotOrDirectoryCreation(@TempDir Path folder) {
        var registry = TemplateRegistry.prepareReload(folder);
        var empty = ResourceLocation.fromNamespaceAndPath("audit", "");
        assertThrows(IllegalArgumentException.class,
                () -> registry.generateTemplateFromNPC(empty, snapshot(root -> fail("Snapshot should not run"))));
        assertFalse(Files.exists(folder.resolve("audit")));
        assertTrue(registry.getAllTemplates().isEmpty());
    }

    @Test void queryCollectionsCannotMutateEitherIndex(@TempDir Path folder) {
        var registry = TemplateRegistry.prepareReload(folder);
        var key = ResourceLocation.fromNamespaceAndPath("audit", "saved");
        registry.generateTemplateFromNPC(key, snapshot(root -> root.setString("name", "Original")));
        Template template = registry.getTemplateByKey(key);
        assertThrows(UnsupportedOperationException.class, () -> registry.getAllTemplates().clear());
        assertThrows(UnsupportedOperationException.class, () -> registry.getTemplates("saved").remove(template));
        assertSame(template, registry.getTemplateByKey(key));
        assertEquals(java.util.List.of(template), java.util.List.copyOf(registry.getTemplates("saved")));
    }

    @Test void querySnapshotsRemainStableWhenATemplateIsReplaced(@TempDir Path folder) {
        var registry = TemplateRegistry.prepareReload(folder);
        var key = ResourceLocation.fromNamespaceAndPath("audit", "saved");
        registry.generateTemplateFromNPC(key, snapshot(root -> root.setString("name", "Old")));
        Template previous = registry.getTemplateByKey(key);
        var all = registry.getAllTemplates(); var named = registry.getTemplates("saved");
        registry.generateTemplateFromNPC(key, snapshot(root -> root.setString("name", "New")));
        assertEquals(java.util.List.of(previous), java.util.List.copyOf(all));
        assertEquals(java.util.List.of(previous), java.util.List.copyOf(named));
        assertNotSame(previous, registry.getTemplateByKey(key));
        assertEquals(java.util.List.of(registry.getTemplateByKey(key)), java.util.List.copyOf(registry.getTemplates("saved")));
    }

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
