package net.citizensnpcs.api.npc.templates;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.TraitTemplateParser;
import net.citizensnpcs.trait.Age;
import net.citizensnpcs.api.util.MemoryDataKey;

/** Opt-in probe for a parser that destroys its target before returning a trait. */
public final class TraitOwnershipProbe {
    public static boolean verifyDefaultParser(NPC npc) {
        var parser = TraitTemplateParser.createDefault(Age.class).getTemplateParser();
        var context = new TraitTemplateParser.TraitParserContext(npc, null);
        var key = new MemoryDataKey(); key.setString("age", "not-an-age");
        Age before = npc.getTraitNullable(Age.class);
        boolean rejected = false;
        try { parser.apply(context, key); }
        catch (IllegalStateException expected) { rejected = expected.getMessage().contains("age"); }
        if (!rejected || npc.getTraitNullable(Age.class) != before) return false;
        key.setInt("age", -6000);
        var parsed = parser.apply(context, key);
        if (!(parsed instanceof Age age) || age.getAge() != -6000) return false;
        npc.addTrait(age);
        return npc.getTraitNullable(Age.class) == age;
    }

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
