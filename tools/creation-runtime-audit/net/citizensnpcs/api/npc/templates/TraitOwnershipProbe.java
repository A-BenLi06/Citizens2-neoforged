package net.citizensnpcs.api.npc.templates;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.TraitTemplateParser;
import net.citizensnpcs.trait.Age;
import net.citizensnpcs.api.util.MemoryDataKey;

/** Opt-in probe for a parser that destroys its target before returning a trait. */
public final class TraitOwnershipProbe {
    public static boolean verify(NPC npc) {
        var calls = new AtomicInteger();
        var definition = new MemoryDataKey();
        definition.setRaw("0", Map.of("name", "remove"));
        definition.setRaw("1", Map.of("name", "later"));
        TraitTemplateParser parser = new TraitTemplateParser() {
            public ShortTemplateParser getShortTemplateParser() { return null; }
            public TemplateParser getTemplateParser() {
                return (context, key) -> {
                    calls.incrementAndGet();
                    Age stale = context.npc.getOrAddTrait(Age.class);
                    context.npc.destroy();
                    return stale;
                };
            }
        };
        var action = new TraitLoaderAction(new TemplateRegistry.TemplateErrorReporter(), null, definition, name -> parser);
        action.accept(npc);
        action.accept(npc);
        return calls.get() == 1 && !npc.getTraits().iterator().hasNext()
                && npc.getOwningRegistry().getById(npc.getId()) != npc;
    }
}
