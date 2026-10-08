package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.*;
import net.citizensnpcs.api.util.MemoryDataKey;

class WaitBehaviorTest {
    @Test void resetDuringDurationEvaluationDiscardsTheOldResult() {
        Behavior[] holder = new Behavior[1];
        var expressions = new ExpressionRegistry();
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "resetwait"; }
            public CompiledExpression compile(String source) {
                return scope -> { holder[0].reset(); return "invalid stale duration"; };
            }
        });
        expressions.setDefaultEngine("resetwait");
        holder[0] = waitFor(expressions, "`duration`", true);
        assertFalse(holder[0].shouldExecute());
        assertEquals(BehaviorStatus.SUCCESS, holder[0].run());
    }

    @Test void nestedActivationRetainsItsOwnDuration() {
        Behavior[] holder = new Behavior[1];
        var evaluations = new AtomicInteger();
        var expressions = new ExpressionRegistry();
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "nestedwait"; }
            public CompiledExpression compile(String source) {
                return scope -> {
                    if (evaluations.incrementAndGet() == 1) {
                        assertTrue(holder[0].shouldExecute());
                        return 100;
                    }
                    return 2;
                };
            }
        });
        expressions.setDefaultEngine("nestedwait");
        holder[0] = waitFor(expressions, "`duration`", true);
        assertFalse(holder[0].shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, holder[0].run());
        assertEquals(BehaviorStatus.SUCCESS, holder[0].run());
        assertEquals(2, evaluations.get());
    }

    @Test void invalidLiteralDurationsAreRejectedBeforeExecution() {
        for (boolean shortForm : new boolean[] { false, true }) {
            for (String input : new String[] { "-1", "not-a-duration", "-0.01s", "2147483648", "100000000d" }) {
                assertThrows(IllegalArgumentException.class, () -> waitFor(new ExpressionRegistry(), input, shortForm), input);
            }
        }
    }

    @Test void expressionNumbersMustBeExactlyRepresentableAsIntegerTicks() {
        var value = new AtomicReference<Object>();
        var expressions = new ExpressionRegistry();
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "exactwait"; }
            public CompiledExpression compile(String source) { return scope -> value.get(); }
        });
        expressions.setDefaultEngine("exactwait");
        Behavior wait = waitFor(expressions, "`duration`", true);
        for (String invalid : new String[] { "1.00000000000000000001", "2147483647.00000001", "-0.00000000000000000001" }) {
            value.set(new java.math.BigDecimal(invalid));
            assertThrows(IllegalArgumentException.class, wait::shouldExecute, invalid);
        }
        value.set(new java.math.BigDecimal("2.00000000000000000000"));
        assertTrue(wait.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, wait.run());
        assertEquals(BehaviorStatus.SUCCESS, wait.run());
        value.set(java.math.BigInteger.valueOf(Integer.MAX_VALUE));
        assertTrue(wait.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, wait.run());
        wait.reset();
    }

    private static Behavior waitFor(ExpressionRegistry expressions, String input, boolean shortForm) {
        var registry = new BehaviorRegistry(expressions);
        var context = new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), expressions, new Memory());
        var params = new MemoryDataKey().getRelative("wait");
        if (shortForm) context.setArgs(new String[]{input}); else params.setString("", input);
        return registry.createBehavior("wait", params, context);
    }

    @Test void literalAndShortFormDurationsCountActualRuns() {
        for (boolean shortForm : new boolean[]{false, true}) {
            Behavior wait = waitFor(new ExpressionRegistry(), "0.15s", shortForm);
            assertTrue(wait.shouldExecute());
            assertEquals(BehaviorStatus.RUNNING, wait.run());
            assertEquals(BehaviorStatus.RUNNING, wait.run());
            assertEquals(BehaviorStatus.SUCCESS, wait.run());
        }
    }

    @Test void zeroWaitAllowsSequenceToContinue() {
        var calls = new AtomicInteger();
        Behavior sequence = Sequence.createSequence(waitFor(new ExpressionRegistry(), "0", false),
                (InstantBehavior) () -> { calls.incrementAndGet(); return BehaviorStatus.SUCCESS; });
        assertTrue(sequence.shouldExecute());
        for (int i = 0; i < 3 && calls.get() == 0; i++) sequence.run();
        assertEquals(1, calls.get());
    }

    @Test void expressionIsEvaluatedOncePerStartAndNeverDuringReset() {
        var evaluations = new AtomicInteger(); var value = new AtomicReference<Object>(2D);
        var expressions = new ExpressionRegistry();
        expressions.registerEngine(new ExpressionEngine() {
            public String getName() { return "waitprobe"; }
            public CompiledExpression compile(String source) {
                return scope -> { evaluations.incrementAndGet(); return value.get(); };
            }
        });
        expressions.setDefaultEngine("waitprobe");
        Behavior wait = waitFor(expressions, "`duration`", true);
        assertTrue(wait.shouldExecute());
        value.set("3t");
        assertEquals(BehaviorStatus.RUNNING, wait.run());
        assertEquals(BehaviorStatus.SUCCESS, wait.run());
        wait.reset(); assertEquals(1, evaluations.get());
        assertTrue(wait.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, wait.run());
        assertEquals(BehaviorStatus.RUNNING, wait.run());
        assertEquals(BehaviorStatus.SUCCESS, wait.run());
        assertEquals(2, evaluations.get());
        for (Object invalid : new Object[]{Double.POSITIVE_INFINITY, Double.NaN, -1D, 0.5D, 2147483648D, true, "-0.01s"}) {
            value.set(invalid);
            assertThrows(IllegalArgumentException.class, wait::shouldExecute);
            assertDoesNotThrow(wait::reset);
        }
    }
}
