package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;

class YamlReplacementActionTest {
    @Test void indexedListDefaultsDoNotMergeIntoAnExistingList() {
        var original = java.util.Map.<String, Object>of("values", java.util.Map.of("0", "keep"));
        var replacement = java.util.Map.<String, Object>of("values", java.util.Map.of("0", "new", "1", "extra"));
        DataKey result = apply(original, replacement, false);
        assertEquals(java.util.Map.of("0", "keep"), result.getRaw("values"));
    }

    @Test void yamlNullDeletesOnlyExistingPathsWhenOverrideIsEnabled(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("delete.yml");
        java.nio.file.Files.writeString(file,
                "replacements:\n  settings:\n    removed: null\n  missing:\n    nested: null\n  scalar:\n    nested: null\n");
        var storage = new net.citizensnpcs.api.util.YamlStorage(file.toFile());
        assertTrue(storage.load());
        var replacements = storage.getKey("replacements").getValuesDeep();
        for (boolean override : new boolean[]{false, true}) {
            var original = new MemoryDataKey();
            original.setInt("settings.removed", 1); original.setInt("settings.kept", 2);
            original.setString("scalar", "keep");
            DataKey result = apply(original.getValuesDeep(), replacements, override);
            assertEquals(!override, result.keyExists("settings.removed"));
            assertEquals(2, result.getInt("settings.kept"));
            assertFalse(result.keyExists("missing"));
            assertEquals("keep", result.getString("scalar"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyReplacementIsolation(boolean failFirstLoad) {
        var input = new java.util.ArrayList<Object>(java.util.List.of("original"));
        DataKey definition = new MemoryDataKey(); definition.setRaw("replacements.items", input);
        var action = PersistenceLoader.loadStrict(YamlReplacementAction.class, definition);
        input.add("external edit");
        var observed = new java.util.ArrayList<java.util.List<Object>>();
        NPC npc = (NPC) java.lang.reflect.Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("saveSnapshot")) return null;
                    if (method.getName().equals("load")) {
                        var values = (java.util.List<Object>) ((DataKey) args[0]).getRaw("items");
                        observed.add(new java.util.ArrayList<>(values));
                        values.add("load mutation");
                        if (failFirstLoad && observed.size() == 1) throw new IllegalStateException("load failure");
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        if (failFirstLoad) assertThrows(IllegalStateException.class, () -> action.accept(npc));
        else action.accept(npc);
        action.accept(npc);
        assertEquals(java.util.List.of(java.util.List.of("original"), java.util.List.of("original")), observed);
        assertEquals(java.util.List.of("original", "external edit"), input);
    }

    @Test void replacementListsAreIndependentForEveryApplication() {
        verifyReplacementIsolation(false);
    }

    @Test void aMutatingFailedLoadCannotPoisonTheNextApplication() {
        verifyReplacementIsolation(true);
    }

    private static DataKey apply(java.util.Map<String, Object> original,
            java.util.Map<String, Object> replacements, boolean override) {
        DataKey definition = new MemoryDataKey();
        definition.setRaw("replacements", replacements);
        definition.setBoolean("override", override);
        var loaded = new java.util.concurrent.atomic.AtomicReference<DataKey>();
        NPC npc = (NPC) java.lang.reflect.Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] {NPC.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("saveSnapshot")) { ((DataKey) args[0]).setRaw("", original); return null; }
                    if (method.getName().equals("load")) { loaded.set(((DataKey) args[0]).copyWithRoot()); return null; }
                    throw new AssertionError(method.getName());
                });
        PersistenceLoader.loadStrict(YamlReplacementAction.class, definition).accept(npc);
        return loaded.get();
    }

    @Test void explicitEmptyMapCanClearExistingMapOrCreateMissingMap() {
        var result = apply(java.util.Map.of("options", java.util.Map.of("old", 1)),
                java.util.Map.of("options", java.util.Map.of(), "new", java.util.Map.of()), true);
        assertEquals(java.util.Map.of(), result.getRaw("options"));
        assertEquals(java.util.Map.of(), result.getRaw("new"));
        assertTrue(result.keyExists("options"));
        assertTrue(result.keyExists("new"));
    }

    @Test void defaultsPreserveExistingMapAndScalarButFillMissingFields() {
        var result = apply(java.util.Map.of("options", java.util.Map.of("old", 1), "scalar", "keep", "dotted", "keep too"),
                java.util.Map.of("options", java.util.Map.of(), "scalar", java.util.Map.of("nested", 2),
                        "new", java.util.Map.of("nested", 3), "dotted.nested", 4), false);
        assertEquals(java.util.Map.of("old", 1), result.getRaw("options"));
        assertEquals("keep", result.getRaw("scalar"));
        assertEquals("keep too", result.getRaw("dotted"));
        assertEquals(3, result.getInt("new.nested"));
    }

    @Test void overridingNestedMapStillReplacesScalarParents() {
        var result = apply(java.util.Map.of("scalar", "old"),
                java.util.Map.of("scalar", java.util.Map.of("nested", 2)), true);
        assertEquals(2, result.getInt("scalar.nested"));
    }

    @Test void partialTemplatePreservesUnsavedNpcState() {
        DataKey template = new MemoryDataKey();
        template.setString("replacements.name", "DefaultName");
        template.setInt("replacements.traits.age.age", -12000);
        var action = PersistenceLoader.loadStrict(YamlReplacementAction.class, template);
        var loaded = new java.util.concurrent.atomic.AtomicReference<DataKey>();
        NPC temporary = (NPC) java.lang.reflect.Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] {NPC.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "save": return null; // Ordinary persistence omits this temporary NPC.
                        case "saveSnapshot":
                            DataKey snapshot = (DataKey) args[0];
                            snapshot.setString("name", "KeepName");
                            snapshot.setString("traitnames", "owner,age");
                            snapshot.setBoolean("metadata.should-save", false);
                            snapshot.setString("traits.owner.owner", "KeepOwner");
                            return null;
                        case "load": loaded.set(((DataKey) args[0]).copyWithRoot()); return null;
                        default: throw new AssertionError(method.getName());
                    }
                });
        action.accept(temporary);
        assertEquals("KeepName", loaded.get().getString("name"));
        assertEquals("KeepOwner", loaded.get().getString("traits.owner.owner"));
        assertFalse(loaded.get().getBoolean("metadata.should-save"));
        assertEquals(-12000, loaded.get().getInt("traits.age.age"));
    }
}
