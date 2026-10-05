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
        action.accept(null);
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
        new TraitLoaderAction(errors, null, key, name -> parser(true, true, calls)).accept(null);
        assertTrue(errors.messages.isEmpty()); assertEquals(2, calls.get());
    }
}
