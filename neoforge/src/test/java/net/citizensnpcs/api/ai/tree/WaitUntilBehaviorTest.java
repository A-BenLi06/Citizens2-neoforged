package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.npc.ai.tree.*;

class WaitUntilBehaviorTest {
    @Test void literalFalseAndZeroWaitWhileTrueAndOneComplete() {
        var expressions = new ExpressionRegistry(); var registry = new BehaviorRegistry(expressions);
        for (String value : new String[]{"false", " FALSE ", "0", "true", "TRUE", "1"}) {
            var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, new Memory());
            context.setArgs(new String[]{value});
            Behavior wait = registry.createBehavior("wait_until", null, context);
            assertTrue(wait.shouldExecute());
            boolean expected = value.trim().equalsIgnoreCase("true") || value.equals("1");
            assertEquals(expected ? BehaviorStatus.SUCCESS : BehaviorStatus.RUNNING, wait.run(), value);
        }
    }

    @Test void namedFalseConditionDoesNotMutateItsConfiguration() {
        var values = java.util.Map.<String, Object>of("condition", "false");
        var params = new MemoryDataKey(values); var expressions = new ExpressionRegistry();
        var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, new Memory());
        assertEquals(BehaviorStatus.RUNNING, new BehaviorRegistry(expressions).createBehavior("wait_until", params, context).run());
        assertEquals(values, params.getValuesDeep());
    }

    @Test void parsedDynamicConditionIsEvaluatedOnEveryRun() {
        var expressions = new ExpressionRegistry(); expressions.registerEngine(new MolangEngine());
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(expressions));
        var memory = new Memory(); memory.set("ready", 0D);
        var root = new MemoryDataKey(); root.setString("root", "wait_until `mem.get('ready')`");
        Behavior wait = parser.parseStrict(root.getRelative("root"), null, new ExpressionScope(), memory);
        assertTrue(wait.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, wait.run());
        memory.set("ready", 1D); assertEquals(BehaviorStatus.SUCCESS, wait.run());
        wait.reset(); memory.set("ready", 0D); assertEquals(BehaviorStatus.RUNNING, wait.run());
    }
}
