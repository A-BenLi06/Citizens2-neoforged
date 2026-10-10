package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class TimerDecoratorTest {
    private static class Child implements Behavior {
        int starts, runs, resets;
        boolean ready = true;
        BehaviorStatus result = BehaviorStatus.RUNNING;
        Runnable onStart = () -> { }, onRun = () -> { }, onReset = () -> { };
        public boolean shouldExecute() { starts++; onStart.run(); return ready; }
        public BehaviorStatus run() { runs++; onRun.run(); return result; }
        public void reset() { resets++; onReset.run(); }
    }

    @Test void permitsCompletionOnEveryLastAllowedTickIncludingOne() {
        for (int ticks : new int[] { 1, 2, 5 }) {
            var child = new Child(); var timer = TimerDecorator.tickLimiter(child, ticks);
            assertTrue(timer.shouldExecute());
            for (int i = 1; i < ticks; i++) assertEquals(BehaviorStatus.RUNNING, timer.run());
            child.result = BehaviorStatus.SUCCESS;
            assertEquals(BehaviorStatus.SUCCESS, timer.run(), "budget=" + ticks);
            assertEquals(ticks, child.runs); assertEquals(1, child.resets);
        }
    }

    @Test void timeoutReleasesResourcesOnceAndNeverRunsBeyondTheBudget() {
        var child = new Child(); var timer = TimerDecorator.tickLimiter(child, 2);
        assertTrue(timer.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, timer.run()); assertEquals(BehaviorStatus.RUNNING, timer.run());
        assertEquals(BehaviorStatus.FAILURE, timer.run()); assertEquals(BehaviorStatus.FAILURE, timer.run());
        assertEquals(2, child.runs); assertEquals(1, child.resets);
        timer.reset(); timer.reset(); assertEquals(1, child.resets);
    }

    @Test void nonpositiveBudgetsDoNotStartTheChild() {
        for (int ticks : new int[] { 0, -1, Integer.MIN_VALUE }) {
            var child = new Child(); var timer = TimerDecorator.tickLimiter(child, ticks);
            assertTrue(timer.shouldExecute()); assertEquals(BehaviorStatus.FAILURE, timer.run());
            timer.reset(); assertEquals(0, child.starts); assertEquals(0, child.runs); assertEquals(0, child.resets);
        }
    }

    @Test void reactivationRestoresBudgetAndReleasesThePreviousAttempt() {
        var child = new Child(); var timer = TimerDecorator.tickLimiter(child, 2);
        assertTrue(timer.shouldExecute()); timer.run();
        assertTrue(timer.shouldExecute()); assertEquals(1, child.resets);
        assertEquals(BehaviorStatus.RUNNING, timer.run()); assertEquals(BehaviorStatus.RUNNING, timer.run());
        assertEquals(BehaviorStatus.FAILURE, timer.run());
        assertEquals(2, child.starts); assertEquals(3, child.runs); assertEquals(2, child.resets);
    }

    @Test void everyTerminalResultLatchesAndCleansTheChildOnce() {
        for (var result : new BehaviorStatus[] { BehaviorStatus.SUCCESS, BehaviorStatus.FAILURE, BehaviorStatus.RESET_AND_REMOVE }) {
            var child = new Child(); child.result = result; var timer = TimerDecorator.tickLimiter(child, 5);
            assertTrue(timer.shouldExecute()); assertEquals(result, timer.run()); assertEquals(result, timer.run());
            timer.reset(); assertEquals(1, child.runs); assertEquals(1, child.resets);
        }
    }

    @Test void rejectedAdmissionReleasesPreparationWithoutExecution() {
        var child = new Child(); child.ready = false; var timer = TimerDecorator.tickLimiter(child, 3);
        assertFalse(timer.shouldExecute()); assertEquals(BehaviorStatus.FAILURE, timer.run());
        timer.reset(); assertEquals(0, child.runs); assertEquals(1, child.resets);
    }

    @Test void admissionAndExecutionErrorsReleaseResourcesAndPreserveCleanupErrors() {
        for (boolean duringStart : new boolean[] { false, true }) {
            var child = new Child(); var timer = TimerDecorator.tickLimiter(child, 3);
            var primary = new IllegalStateException("primary"); var cleanup = new IllegalArgumentException("cleanup");
            child.onReset = () -> { throw cleanup; };
            if (duringStart) child.onStart = () -> { throw primary; };
            else { assertTrue(timer.shouldExecute()); child.onRun = () -> { throw primary; }; }
            assertSame(primary, assertThrows(IllegalStateException.class, () -> {
                if (duringStart) timer.shouldExecute(); else timer.run();
            }));
            assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
            assertEquals(BehaviorStatus.FAILURE, timer.run()); timer.reset(); assertEquals(1, child.resets);
        }
    }

    @Test void cleanupCanStartANewActivationWithoutAnOldResultOverwritingIt() {
        var child = new Child(); var timer = TimerDecorator.tickLimiter(child, 1);
        assertTrue(timer.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, timer.run());
        child.onReset = () -> {
            child.onReset = () -> { }; child.result = BehaviorStatus.SUCCESS;
            assertTrue(timer.shouldExecute());
        };
        assertEquals(BehaviorStatus.RUNNING, timer.run()); assertEquals(BehaviorStatus.SUCCESS, timer.run());
        assertEquals(2, child.starts); assertEquals(2, child.runs); assertEquals(2, child.resets);
    }

    @Test void timerAndTimeoutDeferRunsAndRecursiveAdmissionUntilChildAdmissionFinishes() {
        for (Function<Behavior, Behavior> factory : List.<Function<Behavior, Behavior>>of(
                child -> TimerDecorator.tickLimiter(child, 1), child -> new TimeoutDecorator(child, 1))) {
            var child = new Child(); child.result = BehaviorStatus.SUCCESS;
            Behavior timer = factory.apply(child);
            child.onStart = () -> {
                assertEquals(BehaviorStatus.RUNNING, timer.run());
                assertEquals(0, child.runs);
                assertFalse(timer.shouldExecute());
            };
            assertTrue(timer.shouldExecute()); assertEquals(BehaviorStatus.SUCCESS, timer.run());
            assertEquals(1, child.starts); assertEquals(1, child.runs); assertEquals(1, child.resets);
        }
    }

    @Test void failedAdmissionReleasesTheGuardBeforeCleanupStartsAReplacement() {
        for (Function<Behavior, Behavior> factory : List.<Function<Behavior, Behavior>>of(
                child -> TimerDecorator.tickLimiter(child, 1), child -> new TimeoutDecorator(child, 1))) {
            var child = new Child(); Behavior timer = factory.apply(child);
            var failure = new IllegalStateException("old admission");
            child.onStart = () -> { child.onStart = () -> { }; throw failure; };
            child.onReset = () -> {
                child.onReset = () -> { }; child.result = BehaviorStatus.SUCCESS;
                assertTrue(timer.shouldExecute());
            };
            assertSame(failure, assertThrows(IllegalStateException.class, timer::shouldExecute));
            assertEquals(BehaviorStatus.SUCCESS, timer.run());
            assertEquals(2, child.starts); assertEquals(1, child.runs); assertEquals(2, child.resets);
        }
    }
}
