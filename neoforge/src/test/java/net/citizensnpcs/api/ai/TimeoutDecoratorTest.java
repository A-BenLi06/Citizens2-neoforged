package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class TimeoutDecoratorTest {
    @Test void dynamicBudgetIsCapturedPerActivationAndResetDoesNotEvaluate() {
        var child = new Child(); var budget = new java.util.concurrent.atomic.AtomicInteger(2);
        var evaluations = new java.util.concurrent.atomic.AtomicInteger();
        var timeout = new TimeoutDecorator(child, () -> { evaluations.incrementAndGet(); return budget.get(); });
        assertEquals(0, evaluations.get()); assertTrue(timeout.shouldExecute());
        budget.set(0);
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        assertEquals(BehaviorStatus.FAILURE, timeout.run());
        timeout.reset(); assertEquals(1, evaluations.get());
        assertTrue(timeout.shouldExecute()); assertEquals(BehaviorStatus.FAILURE, timeout.run());
        assertEquals(2, child.calls); assertEquals(2, evaluations.get());
    }

    @Test void throwingBudgetStopsUntilExplicitReactivation() {
        var child = new Child(); var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var evaluations = new java.util.concurrent.atomic.AtomicInteger();
        var timeout = new TimeoutDecorator(child, () -> {
            evaluations.incrementAndGet();
            if (fail.get()) throw new IllegalArgumentException("budget");
            return 1;
        });
        assertThrows(IllegalArgumentException.class, timeout::shouldExecute);
        assertEquals(BehaviorStatus.FAILURE, timeout.run()); assertEquals(0, child.calls);
        timeout.reset(); assertEquals(1, evaluations.get());
        fail.set(false); assertTrue(timeout.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, timeout.run()); assertEquals(BehaviorStatus.FAILURE, timeout.run());
    }

    @Test void resetInsideBudgetCallbackDiscardsTheStaleResult() {
        var child = new Child(); TimeoutDecorator[] holder = new TimeoutDecorator[1];
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        holder[0] = new TimeoutDecorator(child, () -> {
            if (first.getAndSet(false)) { holder[0].reset(); return 100; }
            return 0;
        });
        assertFalse(holder[0].shouldExecute());
        assertEquals(BehaviorStatus.FAILURE, holder[0].run()); assertEquals(0, child.calls);
    }

    private static class Child implements Behavior {
        int calls;
        int resets;
        boolean ready = true;
        Runnable afterReset = () -> { };
        boolean failReset;
        BehaviorStatus status = BehaviorStatus.RUNNING;
        public boolean shouldExecute() { return ready; }
        public BehaviorStatus run() { calls++; return status; }
        public void reset() { resets++; if (failReset) throw new IllegalStateException("reset"); afterReset.run(); }
    }

    @Test void timeoutNeverRunsTheChildBeyondItsBudget() {
        var child = new Child(); var timeout = new TimeoutDecorator(child, 2);
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        assertEquals(BehaviorStatus.FAILURE, timeout.run());
        assertEquals(2, child.calls);
    }

    @Test void completionOnTheLastAllowedTickIsAccepted() {
        var child = new Child(); var timeout = new TimeoutDecorator(child, 2);
        timeout.run(); child.status = BehaviorStatus.SUCCESS;
        assertEquals(BehaviorStatus.SUCCESS, timeout.run());
        assertEquals(2, child.calls);
    }

    @Test void throwingResetDoesNotRetainAnExpiredTimer() {
        var child = new Child(); var timeout = new TimeoutDecorator(child, 1);
        timeout.run(); child.failReset = true;
        assertThrows(IllegalStateException.class, timeout::reset);
        child.failReset = false;
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        assertEquals(2, child.calls);
    }

    @Test void terminalTimeoutAndCompletionDoNotRepeatChildWorkOrCleanup() {
        var child = new Child(); var timeout = new TimeoutDecorator(child, 1);
        timeout.run(); assertEquals(BehaviorStatus.FAILURE, timeout.run());
        assertEquals(BehaviorStatus.FAILURE, timeout.run()); timeout.reset();
        assertEquals(1, child.calls); assertEquals(1, child.resets);
        child.status = BehaviorStatus.SUCCESS;
        assertTrue(timeout.shouldExecute());
        assertEquals(BehaviorStatus.SUCCESS, timeout.run());
        assertEquals(BehaviorStatus.SUCCESS, timeout.run());
        assertEquals(2, child.calls); assertEquals(2, child.resets);
    }

    @Test void newActivationRestoresBudgetAndRejectedStartIsCleaned() {
        var child = new Child(); var timeout = new TimeoutDecorator(child, 1);
        timeout.shouldExecute(); timeout.run(); timeout.run();
        assertTrue(timeout.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        child.ready = false;
        assertFalse(timeout.shouldExecute());
        assertEquals(BehaviorStatus.FAILURE, timeout.run());
        assertEquals(3, child.resets);
    }

    @Test void cleanupReentryPreservesTheNewerTimer() {
        var child = new Child(); var timeout = new TimeoutDecorator(child, 1);
        child.afterReset = () -> { child.afterReset = () -> { }; assertTrue(timeout.shouldExecute()); };
        timeout.run();
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        assertEquals(BehaviorStatus.RUNNING, timeout.run());
        assertEquals(2, child.calls);
    }
}
