package net.citizensnpcs.npc.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.npc.NPC;

class MolangScopeIsolationTest {
    @Test void nestedEvaluationCannotSeeOuterVariablesAndRestoresThemAfterward() throws Exception {
        var expression = new MolangEngine().compile("hook() + query.value");
        var outer = new ExpressionScope(); outer.set("query.value", 4D);
        outer.bind("hook", () -> expression.evaluateAsNumber(new ExpressionScope()));
        assertEquals(4D, expression.evaluateAsNumber(outer));
        assertEquals(0D, expression.evaluateAsNumber(new ExpressionScope()));
    }

    @Test void removedAndOmittedVariablesDoNotLeakAcrossEvaluations() throws Exception {
        var expression = new MolangEngine().compile("query.value + plain");
        var populated = new ExpressionScope(); populated.set("query.value", 4D); populated.set("plain", 3D);
        assertEquals(7D, expression.evaluateAsNumber(populated));
        assertEquals(0D, expression.evaluateAsNumber(new ExpressionScope()));
        populated.remove("plain");
        assertEquals(4D, expression.evaluateAsNumber(populated));
    }

    @Test void temporaryOverridesDoNotEraseStandardBindings() throws Exception {
        var expression = new MolangEngine().compile("math.abs(-2)");
        var overridden = new ExpressionScope(); overridden.set("math", 0D);
        assertEquals(0D, expression.evaluateAsNumber(overridden));
        assertEquals(2D, expression.evaluateAsNumber(new ExpressionScope()));
    }

    @Test void inventoryBindingCannotLeakIntoANestedOrLaterNpcFreeScope() throws Exception {
        var calls = new AtomicInteger();
        var expression = new MolangEngine().compile("inv.has('stone')");
        NPC npc = (NPC) java.lang.reflect.Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] {NPC.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isSpawned")) {
                        if (calls.incrementAndGet() == 1)
                            assertEquals(0D, expression.evaluateAsNumber(new ExpressionScope()));
                        return false;
                    }
                    throw new AssertionError(method.getName());
                });
        var withNpc = new ExpressionScope(); withNpc.setNPC(npc);
        assertEquals(0D, expression.evaluateAsNumber(withNpc));
        assertEquals(1, calls.get());
        assertEquals(0D, expression.evaluateAsNumber(new ExpressionScope()));
        assertEquals(1, calls.get());
    }
}
