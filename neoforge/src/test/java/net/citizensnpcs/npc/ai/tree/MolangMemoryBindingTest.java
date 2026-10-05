package net.citizensnpcs.npc.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.expr.Memory;

class MolangMemoryBindingTest {
    @Test void expressionsCanCountAndClearScalarAndListMemory() throws Exception {
        Memory memory = new Memory();
        evaluate(memory, "mem.set('name', 'value')");
        evaluate(memory, "list.add('items', 7)");
        assertEquals(2D, evaluate(memory, "mem.size()"));
        assertEquals(1D, evaluate(memory, "mem.clear()"));
        assertEquals(0D, evaluate(memory, "mem.size()"));
        assertFalse(memory.has("name"));
        assertFalse(memory.has("items"));
        var saved = new net.citizensnpcs.api.util.MemoryDataKey();
        saved.setString("old", "removed");
        memory.saveTo(saved);
        assertFalse(saved.hasSubKeys());
    }

    @Test void invalidNumericIndicesCannotReadOrRemoveTheFirstSlot() throws Exception {
        for (double index : new double[] {-1, -0.5, 0.5, 2147483648D}) {
            Memory memory = new Memory();
            memory.set("items", List.of(7D, 9D));
            memory.set("index", index);
            assertEquals(0D, evaluate(memory, "list.get('items', mem.get('index'))"));
            assertEquals(0D, evaluate(memory, "list.remove_at('items', mem.get('index'))"));
            assertEquals(List.of(7D, 9D), memory.getList("items"));
        }
    }

    @Test void indexBindingsRequireNumbersAndPreserveValidIntegerBehavior() throws Exception {
        Memory memory = new Memory(); memory.set("items", List.of(7D, 9D));
        assertEquals(0D, evaluate(memory, "list.remove_at('items', 'not-an-index')"));
        assertEquals(9D, evaluate(memory, "list.get('items', 1)"));
        assertEquals(1D, evaluate(memory, "list.remove_at('items', 1)"));
        assertEquals(List.of(7D), memory.getList("items"));
    }

    private static double evaluate(Memory memory, String expression) throws Exception {
        var scope = new ExpressionScope(); scope.setMemory(memory);
        return new MolangEngine().compile(expression).evaluateAsNumber(scope);
    }

    @Test void stringMemoryAndListValuesSurviveExpressionBindings() throws Exception {
        Memory memory = new Memory();
        evaluate(memory, "mem.set('name', 'hello')");
        assertEquals("hello", memory.getString("name"));
        evaluate(memory, "list.add('items', mem.get('name'))");
        assertEquals(List.of("hello"), memory.getList("items"));
        assertEquals(1D, evaluate(memory, "list.contains('items', 'hello')"));
        evaluate(memory, "mem.set('copy', list.get('items', 0))");
        assertEquals("hello", memory.getString("copy"));
        assertEquals(1D, evaluate(memory, "list.remove('items', 'hello')"));
        assertEquals(0, memory.listSize("items"));
        evaluate(memory, "mem.set('fallback', mem.get('missing', 'default'))");
        assertEquals("default", memory.getString("fallback"));
        assertFalse(memory.has("missing"));
    }

    @Test void removingANullSlotReportsSuccessAndQueriesDoNotCreateKeys() throws Exception {
        Memory memory = new Memory(); memory.listSet("items", 0, null);
        assertEquals(1D, evaluate(memory, "list.remove_at('items', 0)"));
        assertEquals(0D, evaluate(memory, "list.remove_at('items', 0)"));
        assertEquals(0D, evaluate(memory, "list.size('absent')"));
        assertFalse(memory.has("absent"));
    }
}
