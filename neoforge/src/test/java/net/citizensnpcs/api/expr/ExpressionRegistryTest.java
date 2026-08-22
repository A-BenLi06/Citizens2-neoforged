package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.expr.ExpressionEngine.ExpressionCompileException;
import net.citizensnpcs.npc.ai.tree.MolangEngine;

/**
 * Checks that Molang expressions really evaluate — the engine is a third-party library reached through several layers of
 * binding code, so "it compiles" says very little about whether an expression written in a shop condition works.
 */
public class ExpressionRegistryTest {
    private ExpressionRegistry registry;

    @BeforeEach
    public void setUp() {
        registry = new ExpressionRegistry();
        registry.registerEngine(new MolangEngine());
    }

    @Test
    public void evaluatesArithmetic() throws ExpressionCompileException {
        assertEquals(7.0, registry.compile("`3 + 4`").evaluateAsNumber(new ExpressionScope()), 1e-9);
    }

    @Test
    public void evaluatesComparisonsAsBooleans() throws ExpressionCompileException {
        assertTrue(registry.compile("`2 > 1`").evaluateAsBoolean(new ExpressionScope()));
        assertFalse(registry.compile("`2 < 1`").evaluateAsBoolean(new ExpressionScope()));
    }

    @Test
    public void readsScopeVariables() throws ExpressionCompileException {
        ExpressionScope scope = new ExpressionScope();
        scope.set("query.health", 12.0);
        assertEquals(12.0, registry.compile("`query.health`").evaluateAsNumber(scope), 1e-9);
        assertTrue(registry.compile("`query.health > 10`").evaluateAsBoolean(scope));
    }

    @Test
    public void lazyBindingsAreOnlyCalledWhenRead() throws ExpressionCompileException {
        int[] calls = { 0 };
        ExpressionScope scope = new ExpressionScope();
        scope.bind("query.expensive", () -> {
            calls[0]++;
            return 5.0;
        });
        CompiledExpression unused = registry.compile("`1 + 1`");
        assertEquals(2.0, unused.evaluateAsNumber(scope), 1e-9);
        assertEquals(0, calls[0], "an expression that never mentions the variable must not compute it");

        assertEquals(5.0, registry.compile("`query.expensive`").evaluateAsNumber(scope), 1e-9);
        assertEquals(1, calls[0]);
    }

    @Test
    public void memoryFunctionsRoundTrip() throws ExpressionCompileException {
        ExpressionScope scope = new ExpressionScope();
        scope.setMemory(new Memory());
        registry.compile("`mem.set('coins', 3)`").evaluate(scope);
        assertEquals(3.0, registry.compile("`mem.get('coins')`").evaluateAsNumber(scope), 1e-9);
        assertTrue(registry.compile("`mem.has('coins')`").evaluateAsBoolean(scope));
        registry.compile("`mem.remove('coins')`").evaluate(scope);
        assertFalse(registry.compile("`mem.has('coins')`").evaluateAsBoolean(scope));
    }

    @Test
    public void listFunctionsRoundTrip() throws ExpressionCompileException {
        ExpressionScope scope = new ExpressionScope();
        scope.setMemory(new Memory());
        registry.compile("`list.add('seen', 1)`").evaluate(scope);
        registry.compile("`list.add('seen', 2)`").evaluate(scope);
        assertEquals(2.0, registry.compile("`list.size('seen')`").evaluateAsNumber(scope), 1e-9);
        assertTrue(registry.compile("`list.contains('seen', 2)`").evaluateAsBoolean(scope));
        registry.compile("`list.clear('seen')`").evaluate(scope);
        assertEquals(0.0, registry.compile("`list.size('seen')`").evaluateAsNumber(scope), 1e-9);
    }

    @Test
    public void defaultMarkupWrapsABareExpression() {
        assertEquals("`1 > 0`", registry.applyDefaultExpressionMarkup("1 > 0"));
        assertEquals("`1 > 0`", registry.applyDefaultExpressionMarkup("`1 > 0`"), "already wrapped, so left alone");
        assertEquals("js`1 > 0`", registry.applyDefaultExpressionMarkup("js`1 > 0`"));
    }

    @Test
    public void unknownEngineIsRejectedRatherThanSilentlyDefaulted() {
        assertThrows(ExpressionCompileException.class, () -> registry.compile("not an expression"));
    }
}
