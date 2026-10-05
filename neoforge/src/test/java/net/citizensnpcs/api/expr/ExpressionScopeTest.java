package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.npc.ai.tree.MolangEngine;

class ExpressionScopeTest {
    @Test void rebindingInvalidatesTheCachedResult() {
        var scope = new ExpressionScope();
        scope.bind("value", () -> 1);
        assertEquals(1, scope.get("value"));
        scope.bind("value", () -> 2);
        assertEquals(2, scope.get("value"));
    }

    @Test void nullResultsAreCachedAndEagerNullShadowsTheParent() {
        var parent = new ExpressionScope(); parent.set("value", "parent");
        var child = parent.createChild(); var calls = new AtomicInteger();
        child.bind("lazy", () -> { calls.incrementAndGet(); return null; });
        assertNull(child.get("lazy")); assertNull(child.get("lazy"));
        assertEquals(1, calls.get());
        child.resetCache(); assertNull(child.get("lazy"));
        assertEquals(2, calls.get());
        child.set("value", null);
        assertTrue(child.has("value")); assertNull(child.get("value"));
    }

    @Test void inheritedNamesAndBindingKindsRespectLocalOverrides() {
        var parent = new ExpressionScope(); parent.bind("value", () -> 1); parent.set("constant", 2);
        var child = parent.createChild();
        Set<String> names = new HashSet<>(); child.getVariableNames().forEach(names::add);
        assertEquals(Set.of("value", "constant"), names);
        assertFalse(child.isConstant("value")); assertNotNull(child.getSupplier("value"));
        child.set("value", 3);
        assertTrue(child.isConstant("value")); assertNull(child.getSupplier("value"));
        assertEquals(3, child.get("value"));
        child.bind("constant", () -> 4);
        assertFalse(child.isConstant("constant")); assertEquals(4, child.get("constant"));
    }

    @Test void molangCanReadInheritedVariablesAndTheirLocalOverrides() throws Exception {
        var parent = new ExpressionScope(); parent.bind("query.inherited", () -> 12D);
        var child = parent.createChild();
        var expression = new MolangEngine().compile("query.inherited + 1");
        assertEquals(13D, expression.evaluateAsNumber(child));
        child.set("query.inherited", 20D);
        assertEquals(21D, expression.evaluateAsNumber(child));
    }
}
