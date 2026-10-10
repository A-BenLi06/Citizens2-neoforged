package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryDecoratorTest {
    private static class Child implements Behavior {
        int starts, runs, resets, ticks;
        boolean ready = true;
        BehaviorStatus result = BehaviorStatus.FAILURE;
        Runnable onStart = () -> { }, onRun = () -> { }, onReset = () -> { };

        public boolean shouldExecute() { starts++; ticks = 2; onStart.run(); return ready; }
        public BehaviorStatus run() {
            runs++;
            assertTrue(ticks > 0, "Each attempt must be admitted before running");
            onRun.run();
            return --ticks > 0 ? BehaviorStatus.RUNNING : result;
        }
        public void reset() { resets++; ticks = 0; onReset.run(); }
    }

    @Test void finiteFailureBudgetSurvivesChildResets() {
        for (int limit : new int[] { 0, 1, 2, 5 }) {
            var calls = new AtomicInteger();
            var retry = RetryDecorator.retry((InstantBehavior) () -> {
                calls.incrementAndGet(); return BehaviorStatus.FAILURE;
            }, limit);
            assertTrue(retry.shouldExecute());
            for (int i = 1; i < Math.max(1, limit); i++) assertEquals(BehaviorStatus.RUNNING, retry.run());
            assertEquals(BehaviorStatus.FAILURE, retry.run(), "limit=" + limit);
            assertEquals(BehaviorStatus.FAILURE, retry.run());
            assertEquals(Math.max(1, limit), calls.get());
        }
    }

    @Test void everyRetryAdmitsAStatefulChildAndYieldsBetweenAttempts() {
        var child = new Child(); var retry = RetryDecorator.retry(child, 3);
        assertTrue(retry.shouldExecute());
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(BehaviorStatus.RUNNING, retry.run());
            assertEquals(attempt < 3 ? BehaviorStatus.RUNNING : BehaviorStatus.FAILURE, retry.run());
            assertEquals(attempt, child.starts);
            assertEquals(attempt, child.resets);
        }
        retry.reset(); retry.reset();
        assertEquals(6, child.runs); assertEquals(3, child.resets);
    }

    @Test void successAndRemovalEndRetriesAndRemainLatched() {
        for (var result : new BehaviorStatus[] { BehaviorStatus.SUCCESS, BehaviorStatus.RESET_AND_REMOVE }) {
            var child = new Child(); var retry = RetryDecorator.retry(child, 4);
            assertTrue(retry.shouldExecute()); retry.run(); retry.run();
            child.result = result;
            assertEquals(BehaviorStatus.RUNNING, retry.run());
            assertEquals(result, retry.run()); assertEquals(result, retry.run());
            retry.reset(); assertEquals(2, child.starts); assertEquals(2, child.resets);
        }
    }

    @Test void unlimitedRetriesContinueUntilSuccessWithoutRestartingWithinOneTick() {
        var child = new Child(); var retry = RetryDecorator.unlimited(child);
        assertTrue(retry.shouldExecute());
        for (int i = 0; i < 20; i++) assertEquals(BehaviorStatus.RUNNING, retry.run());
        assertEquals(10, child.starts); assertEquals(10, child.resets);
        child.result = BehaviorStatus.SUCCESS;
        assertEquals(BehaviorStatus.RUNNING, retry.run()); assertEquals(BehaviorStatus.SUCCESS, retry.run());
        assertEquals(11, child.starts); assertEquals(11, child.resets);
    }

    @Test void resetCancelsWorkAndNewActivationGetsTheFullBudget() {
        var child = new Child(); var retry = RetryDecorator.retry(child, 2);
        assertTrue(retry.shouldExecute()); retry.run(); retry.reset();
        assertEquals(BehaviorStatus.FAILURE, retry.run()); assertEquals(1, child.runs);
        assertTrue(retry.shouldExecute());
        for (int i = 0; i < 3; i++) assertEquals(BehaviorStatus.RUNNING, retry.run());
        assertEquals(BehaviorStatus.FAILURE, retry.run());
        assertEquals(3, child.starts); assertEquals(3, child.resets);
    }

    @Test void rejectedAdmissionStopsWithoutRunningAndReleasesPreparation() {
        var child = new Child(); var retry = RetryDecorator.retry(child, 3);
        child.ready = false;
        assertFalse(retry.shouldExecute()); assertEquals(BehaviorStatus.FAILURE, retry.run());
        assertEquals(0, child.runs); assertEquals(1, child.resets);
        child.ready = true; assertTrue(retry.shouldExecute()); retry.run(); retry.run();
        child.ready = false;
        assertEquals(BehaviorStatus.FAILURE, retry.run());
        assertEquals(2, child.runs); assertEquals(3, child.resets);
        retry.reset(); assertEquals(3, child.resets);
    }

    @Test void executionAndAdmissionErrorsStopRetriesAndPreserveCleanupErrors() {
        for (String phase : new String[] { "initial", "next", "run" }) {
            var child = new Child(); var retry = RetryDecorator.retry(child, 3);
            var failure = new IllegalStateException(phase);
            var cleanup = new IllegalArgumentException("cleanup");
            if (!phase.equals("initial")) assertTrue(retry.shouldExecute());
            if (phase.equals("next")) { retry.run(); retry.run(); }
            if (phase.equals("run")) child.onRun = () -> { throw failure; };
            else child.onStart = () -> { throw failure; };
            child.onReset = () -> { throw cleanup; };
            assertSame(failure, assertThrows(IllegalStateException.class, () -> {
                if (phase.equals("initial")) retry.shouldExecute(); else retry.run();
            }));
            assertArrayEquals(new Throwable[] { cleanup }, failure.getSuppressed());
            int starts = child.starts, runs = child.runs, resets = child.resets;
            assertEquals(BehaviorStatus.FAILURE, retry.run()); retry.reset();
            assertEquals(starts, child.starts); assertEquals(runs, child.runs); assertEquals(resets, child.resets);
        }
    }

    @Test void cleanupFailureStopsRetryAndAllowsExplicitRecovery() {
        var child = new Child(); var retry = RetryDecorator.retry(child, 3);
        assertTrue(retry.shouldExecute()); retry.run();
        var failure = new IllegalStateException("cleanup"); child.onReset = () -> { throw failure; };
        assertSame(failure, assertThrows(IllegalStateException.class, retry::run));
        assertEquals(BehaviorStatus.FAILURE, retry.run()); retry.reset();
        assertEquals(1, child.starts); assertEquals(1, child.resets);
        child.onReset = () -> { }; child.result = BehaviorStatus.SUCCESS;
        assertTrue(retry.shouldExecute()); retry.run(); assertEquals(BehaviorStatus.SUCCESS, retry.run());
    }

    @Test void callbackResetStopsTheOldAttemptBeforeItCanRetry() {
        var child = new Child(); var retry = RetryDecorator.retry(child, 3);
        child.onReset = retry::reset;
        assertTrue(retry.shouldExecute()); retry.run();
        assertEquals(BehaviorStatus.RUNNING, retry.run());
        assertEquals(BehaviorStatus.FAILURE, retry.run());
        assertEquals(1, child.starts); assertEquals(1, child.resets);
    }

    @Test void callbackReplacementSurvivesAStaleCleanupFailure() {
        var child = new Child(); var retry = RetryDecorator.retry(child, 3);
        assertTrue(retry.shouldExecute()); retry.run();
        var failure = new IllegalStateException("retired attempt");
        child.onReset = () -> {
            child.onReset = () -> { }; child.result = BehaviorStatus.SUCCESS;
            assertTrue(retry.shouldExecute()); throw failure;
        };
        assertSame(failure, assertThrows(IllegalStateException.class, retry::run));
        assertEquals(BehaviorStatus.RUNNING, retry.run()); assertEquals(BehaviorStatus.SUCCESS, retry.run());
        assertEquals(2, child.starts); assertEquals(2, child.resets);
    }

    @Test void recursiveAdmissionAndRunsDoNotExecuteChildTwice() {
        var child = new Child(); var retry = RetryDecorator.retry(child, 2);
        child.onStart = () -> {
            assertFalse(retry.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, retry.run());
        };
        child.onRun = () -> assertEquals(BehaviorStatus.RUNNING, retry.run());
        assertTrue(retry.shouldExecute());
        for (int i = 0; i < 3; i++) assertEquals(BehaviorStatus.RUNNING, retry.run());
        assertEquals(BehaviorStatus.FAILURE, retry.run());
        assertEquals(2, child.starts); assertEquals(4, child.runs); assertEquals(2, child.resets);
    }
}
