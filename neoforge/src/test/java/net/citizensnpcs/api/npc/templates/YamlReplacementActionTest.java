package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;

class YamlReplacementActionTest {
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
