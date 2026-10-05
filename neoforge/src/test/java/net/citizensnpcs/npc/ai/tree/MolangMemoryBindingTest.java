package net.citizensnpcs.npc.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.expr.Memory;

class MolangMemoryBindingTest {
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
