package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.npc.ai.tree.*;

class WaitUntilBehaviorTest {
    @Test void brokenConditionCompilationIsRejectedWithItsCause() {
        var expressions = new ExpressionRegistry();
        var cause = new ExpressionEngine.ExpressionCompileException("invalid condition");
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "brokencondition"; }
            public CompiledExpression compile(String source) throws ExpressionCompileException { throw cause; }
        });
        expressions.setDefaultEngine("brokencondition");
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(expressions));
        var root = new MemoryDataKey(); root.setString("wait_until", "`broken`" );
        var failure = assertThrows(IllegalArgumentException.class,
                () -> parser.parseStrict(root.getRelative("wait_until"), null, new ExpressionScope(), new Memory()));
        assertSame(cause, failure.getCause());
    }

    @Test void unavailableConditionLanguageIsRejectedInsteadOfWaitingForever() {
        var expressions = new ExpressionRegistry(); expressions.registerEngine(new MolangEngine());
        var parser = new BehaviorTreeParser(new CitizensBehaviorRegistry(expressions));
        var root = new MemoryDataKey(); root.setString("wait_until", "missinglanguage`1`" );
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseStrict(root.getRelative("wait_until"), null, new ExpressionScope(), new Memory()));
    }

    private static Behavior dynamic(java.util.function.Supplier<Object> condition) {
        var expressions = new ExpressionRegistry();
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "conditionprobe"; }
            public CompiledExpression compile(String source) { return scope -> condition.get(); }
        });
        expressions.setDefaultEngine("conditionprobe");
        var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, new Memory());
        context.setArgs(new String[] { "`condition`" });
        return new BehaviorRegistry(expressions).createBehavior("wait_until", null, context);
    }

    @Test void resetOrRestartDuringConditionEvaluationDiscardsOldSuccess() {
        for (boolean restart : new boolean[] { false, true }) {
            Behavior[] holder = new Behavior[1];
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            holder[0] = dynamic(() -> {
                if (calls.incrementAndGet() == 1) {
                    if (restart) assertTrue(holder[0].shouldExecute()); else holder[0].reset();
                    return true;
                }
                return false;
            });
            assertTrue(holder[0].shouldExecute());
            assertEquals(BehaviorStatus.RUNNING, holder[0].run());
            assertEquals(BehaviorStatus.RUNNING, holder[0].run());
            assertEquals(2, calls.get());
        }
    }

    @Test void recursiveRunCannotEvaluateConditionAgain() {
        Behavior[] holder = new Behavior[1]; var calls = new java.util.concurrent.atomic.AtomicInteger();
        holder[0] = dynamic(() -> {
            calls.incrementAndGet();
            assertEquals(BehaviorStatus.RUNNING, holder[0].run());
            return true;
        });
        assertTrue(holder[0].shouldExecute()); assertEquals(BehaviorStatus.SUCCESS, holder[0].run());
        assertEquals(1, calls.get());
    }

    @Test void conditionFailureDoesNotLeaveEvaluationGuardLocked() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failure = new IllegalStateException("condition");
        Behavior wait = dynamic(() -> {
            if (calls.incrementAndGet() == 1) throw failure;
            return true;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, wait::run));
        wait.reset(); assertTrue(wait.shouldExecute());
        assertEquals(BehaviorStatus.SUCCESS, wait.run()); assertEquals(2, calls.get());
    }

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
