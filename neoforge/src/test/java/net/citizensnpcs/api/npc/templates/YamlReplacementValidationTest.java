package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;

class YamlReplacementValidationTest {
    private static String causeMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage();
    }

    @Test void malformedReplacementMapsAreRejected() {
        for (Object value : List.of("name", 42, List.of("entry"))) {
            var key = new MemoryDataKey(); key.setRaw("replacements", value);
            var failure = assertThrows(RuntimeException.class, () -> PersistenceLoader.loadStrict(YamlReplacementAction.class, key));
            assertTrue(causeMessage(failure).contains("replacements must be a mapping"));
        }
    }

    @Test void invalidOverrideValuesAreNotSilentlyFalse() {
        for (Object value : List.of("perhaps", 1, List.of(true))) {
            var key = new MemoryDataKey(); key.setRaw("override", value);
            var failure = assertThrows(RuntimeException.class, () -> PersistenceLoader.loadStrict(YamlReplacementAction.class, key));
            assertTrue(causeMessage(failure).contains("override must be a boolean"));
        }
    }

    @Test void booleanAndQuotedBooleanOptionsControlReplacement() {
        for (Object value : List.of(true, false, "TRUE", " false ")) {
            var key = new MemoryDataKey(); key.setRaw("override", value); key.setString("replacements.name", "New");
            var result = new java.util.concurrent.atomic.AtomicReference<DataKey>();
            NPC npc = (NPC) java.lang.reflect.Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("saveSnapshot")) { ((DataKey) args[0]).setString("name", "Old"); return null; }
                        if (method.getName().equals("load")) { result.set(((DataKey) args[0]).copyWithRoot()); return null; }
                        throw new AssertionError(method.getName());
                    });
            PersistenceLoader.loadStrict(YamlReplacementAction.class, key).accept(npc);
            assertEquals(Boolean.parseBoolean(value.toString().strip()) ? "New" : "Old", result.get().getString("name"));
        }
    }
}
