package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.npc.ai.tree.MolangEngine;

class ExpressionScopeTest {
    @Test void directCycleReportsItsBindingAndCanBeRepaired() {
        var scope = new ExpressionScope(); scope.bind("cycle", () -> scope.get("cycle"));
        var failure = assertThrows(IllegalStateException.class, () -> scope.get("cycle"));
        assertTrue(failure.getMessage().contains("cycle"));
        scope.bind("cycle", () -> 7);
        assertEquals(7, scope.get("cycle"));
    }

    @Test void cycleAcrossParentAndChildScopesReleasesAllEvaluationGuards() {
        var parent = new ExpressionScope(); var child = parent.createChild();
        parent.bind("first", () -> child.get("second"));
        child.bind("second", () -> parent.get("first"));
        assertThrows(IllegalStateException.class, () -> parent.get("first"));
        child.set("second", 5);
        assertEquals(5, parent.get("first"));
    }

    @Test void aNewBindingMayBeEvaluatedInsideThePreviousSuppliersCall() {
        var scope = new ExpressionScope(); var calls = new AtomicInteger();
        scope.bind("value", () -> {
            scope.bind("value", () -> { calls.incrementAndGet(); return 9; });
            return scope.get("value");
        });
        assertEquals(9, scope.get("value"));
        assertEquals(9, scope.get("value"));
        assertEquals(1, calls.get());
    }

    @Test void supplierRebindingItselfCannotCacheItsOldResult() {
        var scope = new ExpressionScope();
        scope.bind("value", () -> { scope.bind("value", () -> 2); return 1; });
        assertEquals(1, scope.get("value"));
        assertEquals(2, scope.get("value"));
    }

    @Test void rebindingTheIdenticalSupplierStillCreatesANewBinding() {
        var scope = new ExpressionScope(); var calls = new AtomicInteger();
        java.util.function.Supplier<?>[] supplier = new java.util.function.Supplier<?>[1];
        supplier[0] = () -> {
            int value = calls.incrementAndGet();
            if (value == 1) scope.bind("value", supplier[0]);
            return value;
        };
        scope.bind("value", supplier[0]);
        assertEquals(1, scope.get("value"));
        assertEquals(2, scope.get("value"));
        assertEquals(2, scope.get("value"));
    }

    @Test void removedBindingsAndResetCyclesRejectInFlightCacheWrites() {
        var parent = new ExpressionScope(); parent.set("value", 9);
        var scope = parent.createChild();
        scope.bind("value", () -> { scope.remove("value"); return 1; });
        assertEquals(1, scope.get("value"));
        assertEquals(9, scope.get("value"));
        var calls = new AtomicInteger();
        scope.bind("resetting", () -> { scope.resetCache(); return calls.incrementAndGet(); });
        assertEquals(1, scope.get("resetting"));
        assertEquals(2, scope.get("resetting"));
    }

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
