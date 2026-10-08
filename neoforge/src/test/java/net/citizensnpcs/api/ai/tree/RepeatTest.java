package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RepeatTest {
    private static class Child implements Behavior {
        int starts, runs, resets, phase;
        BehaviorStatus result = BehaviorStatus.SUCCESS;
        boolean ready = true;
        Runnable afterReset = () -> { };
        Runnable afterStart = () -> { }, afterRun = () -> { };
        public boolean shouldExecute() { starts++; afterStart.run(); return ready; }
        public BehaviorStatus run() { runs++; afterRun.run(); return ++phase == 1 ? BehaviorStatus.RUNNING : result; }
        public void reset() { resets++; phase = 0; afterReset.run(); }
    }

    @Test void startAndRunFailuresReleaseResourcesAndStopRepetition() {
        for (String phase : new String[] { "initial", "next", "run" }) {
            var child = new Child(); var repeat = new Repeat(child, () -> 3);
            var primary = new IllegalStateException(phase);
            var cleanup = new IllegalArgumentException("cleanup");
            if (!phase.equals("initial")) assertTrue(repeat.shouldExecute());
            if (phase.equals("next")) {
                assertEquals(BehaviorStatus.RUNNING, repeat.run());
                assertEquals(BehaviorStatus.RUNNING, repeat.run());
            }
            if (phase.equals("run")) child.afterRun = () -> { throw primary; };
            else child.afterStart = () -> { throw primary; };
            child.afterReset = () -> { throw cleanup; };
            assertSame(primary, assertThrows(IllegalStateException.class, () -> {
                if (phase.equals("initial")) repeat.shouldExecute(); else repeat.run();
            }));
            assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
            int runs = child.runs, starts = child.starts, resets = child.resets;
            assertEquals(BehaviorStatus.FAILURE, repeat.run());
            repeat.reset();
            assertEquals(runs, child.runs); assertEquals(starts, child.starts); assertEquals(resets, child.resets);
            child.afterStart = child.afterRun = child.afterReset = () -> { };
            assertTrue(repeat.shouldExecute());
            for (int i = 0; i < 5; i++) assertEquals(BehaviorStatus.RUNNING, repeat.run());
            assertEquals(BehaviorStatus.SUCCESS, repeat.run());
        }
    }

    @Test void cleanupFailureCannotStartTheRemainingIterations() {
        var child = new Child(); var repeat = new Repeat(child, () -> 3);
        var failure = new IllegalStateException("cleanup");
        child.afterReset = () -> { throw failure; };
        assertTrue(repeat.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, repeat.run());
        assertSame(failure, assertThrows(IllegalStateException.class, repeat::run));
        assertEquals(BehaviorStatus.FAILURE, repeat.run());
        repeat.reset(); assertEquals(1, child.starts); assertEquals(2, child.runs); assertEquals(1, child.resets);
    }

    @Test void invalidOrThrowingCountsLeaveAStoppedFailureWithoutStartingTheChild() {
        for (boolean throwing : new boolean[] { false, true }) {
            var child = new Child();
            var repeat = new Repeat(child, () -> {
                if (throwing) throw new IllegalArgumentException("count");
                return -1;
            });
            assertThrows(IllegalArgumentException.class, repeat::shouldExecute);
            assertEquals(BehaviorStatus.FAILURE, repeat.run());
            repeat.reset(); assertEquals(0, child.starts); assertEquals(0, child.runs); assertEquals(0, child.resets);
        }
    }

    @Test void aStaleFailureCannotAbortAReplacementActivation() {
        for (boolean fromCleanup : new boolean[] { false, true }) {
            var child = new Child(); var count = new AtomicInteger(3);
            var repeat = new Repeat(child, count::get);
            var failure = new IllegalStateException("old activation");
            Runnable replace = () -> {
                child.afterRun = child.afterReset = () -> { };
                count.set(1); assertTrue(repeat.shouldExecute()); throw failure;
            };
            assertTrue(repeat.shouldExecute());
            if (fromCleanup) {
                assertEquals(BehaviorStatus.RUNNING, repeat.run());
                child.afterReset = replace;
            } else child.afterRun = replace;
            assertSame(failure, assertThrows(IllegalStateException.class, repeat::run));
            assertEquals(2, child.starts); assertEquals(1, child.resets);
            assertEquals(BehaviorStatus.RUNNING, repeat.run());
            assertEquals(BehaviorStatus.SUCCESS, repeat.run());
            assertEquals(2, child.resets);
        }
    }

    @Test void eachIterationStartsRunsAndResetsItsChild() {
        var child = new Child(); var repeat = new Repeat(child, () -> 2);
        assertTrue(repeat.shouldExecute());
        for (int i = 0; i < 3; i++) assertEquals(BehaviorStatus.RUNNING, repeat.run());
        assertEquals(BehaviorStatus.SUCCESS, repeat.run());
        assertEquals(2, child.starts); assertEquals(4, child.runs); assertEquals(2, child.resets);
        repeat.reset(); assertEquals(2, child.resets);
    }

    @Test void zeroAndDynamicCountsAreEvaluatedOnlyOnActivation() {
        var count = new AtomicInteger(); var evaluations = new AtomicInteger(); var child = new Child();
        var repeat = new Repeat(child, () -> { evaluations.incrementAndGet(); return count.get(); });
        assertTrue(repeat.shouldExecute()); assertEquals(BehaviorStatus.SUCCESS, repeat.run());
        assertEquals(0, child.starts);
        count.set(1); repeat.reset(); assertEquals(1, evaluations.get());
        assertTrue(repeat.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, repeat.run());
        assertEquals(BehaviorStatus.SUCCESS, repeat.run()); assertEquals(2, evaluations.get());
    }

    @Test void failureAndRemovalStopRepetitionAndResetReleasesActiveWork() {
        for (BehaviorStatus status : new BehaviorStatus[]{BehaviorStatus.FAILURE, BehaviorStatus.RESET_AND_REMOVE}) {
            var child = new Child(); child.result = status; var repeat = new Repeat(child, () -> 3);
            assertTrue(repeat.shouldExecute()); repeat.run();
            assertEquals(status, repeat.run()); assertEquals(status, repeat.run());
            assertEquals(1, child.starts); assertEquals(1, child.resets);
        }
        var child = new Child(); var repeat = new Repeat(child, () -> 3);
        repeat.shouldExecute(); repeat.run(); repeat.reset();
        assertEquals(1, child.resets);
        child.ready = false; assertFalse(repeat.shouldExecute()); assertEquals(2, child.resets);
    }

    @Test void resetFromChildCleanupStopsTheRemainingIterations() {
        var child = new Child(); var repeat = new Repeat(child, () -> 3);
        child.afterReset = repeat::reset;
        assertTrue(repeat.shouldExecute()); repeat.run(); repeat.run();
        assertEquals(BehaviorStatus.SUCCESS, repeat.run());
        assertEquals(2, child.runs); assertEquals(1, child.resets);
    }

    @Test void newerActivationFromCleanupIsNotOverwritten() {
        var child = new Child(); var count = new AtomicInteger(3); var evaluations = new AtomicInteger();
        var repeat = new Repeat(child, () -> { evaluations.incrementAndGet(); return count.get(); });
        assertTrue(repeat.shouldExecute());
        child.afterReset = () -> {
            child.afterReset = () -> { };
            count.set(1);
            assertTrue(repeat.shouldExecute());
        };
        assertFalse(repeat.shouldExecute());
        assertEquals(2, evaluations.get());
        assertEquals(BehaviorStatus.RUNNING, repeat.run());
        assertEquals(BehaviorStatus.SUCCESS, repeat.run());
        assertEquals(2, child.starts);
    }
}
