package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.trait.TraitTemplateParser;
import net.citizensnpcs.api.util.MemoryDataKey;

class TraitTemplateFormTest {
    private static net.citizensnpcs.api.npc.NPC npc() {
        var reference = new java.util.concurrent.atomic.AtomicReference<net.citizensnpcs.api.npc.NPC>();
        var registry = (net.citizensnpcs.api.npc.NPCRegistry) java.lang.reflect.Proxy.newProxyInstance(
                net.citizensnpcs.api.npc.NPCRegistry.class.getClassLoader(), new Class<?>[]{net.citizensnpcs.api.npc.NPCRegistry.class},
                (proxy, method, args) -> reference.get());
        var uuid = java.util.UUID.randomUUID();
        var npc = (net.citizensnpcs.api.npc.NPC) java.lang.reflect.Proxy.newProxyInstance(
                net.citizensnpcs.api.npc.NPC.class.getClassLoader(), new Class<?>[]{net.citizensnpcs.api.npc.NPC.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOwningRegistry" -> registry;
                    case "getId" -> 1;
                    case "getUniqueId" -> uuid;
                    default -> throw new AssertionError(method.getName());
                });
        reference.set(npc);
        return npc;
    }
    @Test void malformedCollectionsAndEntriesAreReportedBeforeParserLookup() {
        for (Object value : List.of("known", Map.of("named", "known"), List.of(42), List.of(List.of("known")))) {
            var container = new MemoryDataKey(); container.setRaw("traits", value);
            var errors = new Errors();
            new TraitLoaderAction(errors, null, container.getRelative("traits"), name -> { fail("Unexpected parser lookup"); return null; });
            assertFalse(errors.messages.isEmpty());
        }
    }

    @Test void directListsSupportBothTemplateForms() {
        var container = new MemoryDataKey(); container.setRaw("traits", List.of("known argument", Map.of("name", "known")));
        var errors = new Errors(); var calls = new AtomicInteger();
        new TraitLoaderAction(errors, null, container.getRelative("traits"), name -> parser(true, true, calls)).accept(npc());
        assertTrue(errors.messages.isEmpty()); assertEquals(2, calls.get());
    }

    @Test void emptyCollectionsRemainValid() {
        for (Object value : List.of(List.of(), Map.of())) {
            var container = new MemoryDataKey(); container.setRaw("traits", value);
            var errors = new Errors();
            new TraitLoaderAction(errors, null, container.getRelative("traits"), name -> { fail("Unexpected parser lookup"); return null; });
            assertTrue(errors.messages.isEmpty());
        }
    }

    @Test void structuredTraitNameMustBeANonemptyString() {
        for (Object name : List.of(42, " ")) {
            var container = new MemoryDataKey(); container.setRaw("0", Map.of("name", name));
            var errors = new Errors();
            new TraitLoaderAction(errors, null, container, value -> { fail("Unexpected parser lookup"); return null; });
            assertTrue(errors.messages.get(0).contains("non-empty string"));
        }
    }

    @Test void structuredActionsFreezeSourceAndIsolateEachParserInvocation() {
        var key = new MemoryDataKey(); key.setString("0.name", "known"); key.setInt("0.settings.value", 1);
        List<Integer> observed = new ArrayList<>();
        TraitTemplateParser parser = new TraitTemplateParser() {
            public ShortTemplateParser getShortTemplateParser() { return null; }
            public TemplateParser getTemplateParser() {
                return (context, data) -> {
                    observed.add(data.getInt("settings.value")); data.setInt("settings.value", 99); return null;
                };
            }
        };
        var action = new TraitLoaderAction(new Errors(), null, key, name -> parser);
        key.setInt("0.settings.value", 7);
        action.accept(npc()); action.accept(npc());
        assertEquals(List.of(1, 1), observed);
        assertEquals(7, key.getInt("0.settings.value"));
    }

    @Test void shortActionsDoNotShareMutableCommandFlags() {
        var key = new MemoryDataKey(); key.setString("0", "known --value 7");
        List<String> observed = new ArrayList<>();
        TraitTemplateParser parser = new TraitTemplateParser() {
            public ShortTemplateParser getShortTemplateParser() {
                return (context, command) -> {
                    observed.add(command.getValueFlags().get("value")); command.getValueFlags().clear(); return null;
                };
            }
            public TemplateParser getTemplateParser() { return null; }
        };
        var action = new TraitLoaderAction(new Errors(), null, key, name -> parser);
        action.accept(npc()); action.accept(npc());
        assertEquals(List.of("7", "7"), observed);
    }

    private static class Errors extends TemplateRegistry.TemplateErrorReporter {
        final List<String> messages = new ArrayList<>();
        @Override public void addError(String message) { super.addError(message); messages.add(message); }
    }

    private static TraitTemplateParser parser(boolean shortForm, boolean structured, AtomicInteger calls) {
        return new TraitTemplateParser() {
            public ShortTemplateParser getShortTemplateParser() {
                return shortForm ? (context, key) -> { calls.incrementAndGet(); return null; } : null;
            }
            public TemplateParser getTemplateParser() {
                return structured ? (context, key) -> { calls.incrementAndGet(); return null; } : null;
            }
        };
    }

    @Test void unsupportedStructuredFormIsReported() {
        var errors = new Errors(); var calls = new AtomicInteger(); var key = new MemoryDataKey();
        key.setRaw("0", Map.of("name", "known"));
        var action = new TraitLoaderAction(errors, null, key, name -> parser(true, false, calls));
        action.accept(npc());
        assertEquals(1, errors.messages.size());
        assertTrue(errors.messages.get(0).contains("known does not support structured"));
        assertEquals(0, calls.get());
    }

    @Test void unsupportedShortFormIsReported() {
        var errors = new Errors(); var calls = new AtomicInteger(); var key = new MemoryDataKey();
        key.setString("0", "known argument");
        new TraitLoaderAction(errors, null, key, name -> parser(false, true, calls));
        assertEquals(1, errors.messages.size());
        assertTrue(errors.messages.get(0).contains("known does not support short"));
    }

    @Test void supportedFormsStillApply() {
        var errors = new Errors(); var calls = new AtomicInteger(); var key = new MemoryDataKey();
        key.setRaw("0", Map.of("name", "known")); key.setString("1", "known argument");
        new TraitLoaderAction(errors, null, key, name -> parser(true, true, calls)).accept(npc());
        assertTrue(errors.messages.isEmpty()); assertEquals(2, calls.get());
    }
}
