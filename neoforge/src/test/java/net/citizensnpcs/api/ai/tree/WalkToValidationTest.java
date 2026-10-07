package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.npc.NPC;

class WalkToValidationTest {
    @Test void namedCoordinatesCanBeReadFromImmutableConfiguration() {
        var values = java.util.Map.<String, Object>of("x", "1", "y", "2", "z", "3");
        var params = new net.citizensnpcs.api.util.MemoryDataKey(values);
        var expressions = new ExpressionRegistry();
        var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, new Memory());
        assertNotNull(new BehaviorRegistry(expressions).createBehavior("walkto", params, context));
        assertEquals(values, params.getValuesDeep());
    }

    @Test void missingNamedCoordinatesDoNotAttemptToWriteDefaults() {
        var params = new net.citizensnpcs.api.util.MemoryDataKey(java.util.Map.of("x", "1"));
        var expressions = new ExpressionRegistry();
        var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, new Memory());
        assertNull(new BehaviorRegistry(expressions).createBehavior("walkto", params, context));
        assertEquals(java.util.Map.of("x", "1"), params.getValuesDeep());
    }
    @Test void invalidInputsFailBeforeAccessingOrChangingNavigation() {
        NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> { if (method.getName().equals("isSpawned")) return true; throw new AssertionError(method.getName()); });
        for (String[] args : new String[][] {
                {"NaN", "0", "0"}, {"0", "Infinity", "0"}, {"0", "0", "-Infinity"},
                {"0", "0", "0", "speed=1e100"}, {"0", "0", "0", "speed=-1"},
                {"0", "0", "0", "range=NaN"}, {"0", "0", "0", "range=-1"},
                {"0", "0", "0", "margin=Infinity"}, {"0", "0", "0", "margin=-1"}}) {
            var expressions = new ExpressionRegistry();
            var context = new BehaviorRegistry.BehaviorContext(npc, new ExpressionScope(), expressions, new Memory());
            context.setArgs(args);
            Behavior behavior = new BehaviorRegistry(expressions).createBehavior("walkto", null, context);
            assertEquals(BehaviorStatus.FAILURE, behavior.run());
        }
    }
}
