package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoopTest {
    private static class Child implements Behavior {
        int starts, runs, resets;
        boolean ready = true;
        BehaviorStatus status = BehaviorStatus.SUCCESS;
        Runnable onStart = () -> { }, onRun = () -> { }, onReset = () -> { };
        public boolean shouldExecute() { starts++; onStart.run(); return ready; }
        public BehaviorStatus run() { runs++; onRun.run(); return status; }
        public void reset() { resets++; onReset.run(); }
    }

    @Test void successfulIterationsEvaluateAfterCleanupAndStopPermanently() {
        var child = new Child(); var checks = new AtomicInteger();
        var loop = new Loop(child, () -> {
            assertEquals(child.runs, child.resets);
            checks.incrementAndGet(); return child.runs < 3;
        });
        assertTrue(loop.shouldExecute()); assertEquals(0, checks.get());
        assertEquals(BehaviorStatus.RUNNING, loop.run());
        assertEquals(BehaviorStatus.RUNNING, loop.run());
        assertEquals(BehaviorStatus.SUCCESS, loop.run());
        assertEquals(BehaviorStatus.SUCCESS, loop.run());
        loop.reset(); loop.reset();
        assertEquals(3, child.starts); assertEquals(3, child.runs); assertEquals(3, child.resets);
        assertEquals(3, checks.get());
        assertTrue(loop.shouldExecute()); assertEquals(BehaviorStatus.SUCCESS, loop.run());
        assertEquals(4, child.runs);
    }

    @Test void runningFailureAndRemovalRetainTheirStatusesWithoutExtraEffects() {
        for (BehaviorStatus terminal : new BehaviorStatus[] { BehaviorStatus.FAILURE, BehaviorStatus.RESET_AND_REMOVE }) {
            var child = new Child(); var checks = new AtomicInteger();
            var loop = new Loop(child, () -> { checks.incrementAndGet(); return true; });
            child.status = BehaviorStatus.RUNNING;
            assertTrue(loop.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, loop.run());
            assertEquals(0, child.resets);
            child.status = terminal;
            assertEquals(terminal, loop.run()); assertEquals(terminal, loop.run());
            loop.reset(); assertEquals(2, child.runs); assertEquals(1, child.resets);
            assertEquals(0, checks.get());
        }
    }

    @Test void rejectedStartsReleaseResourcesAndDoNotRun() {
        var child = new Child(); child.ready = false;
        var loop = new Loop(child, () -> true);
        assertFalse(loop.shouldExecute()); assertEquals(BehaviorStatus.FAILURE, loop.run());
        assertEquals(0, child.runs); assertEquals(1, child.resets);
        child.ready = true; assertTrue(loop.shouldExecute());
        child.ready = false;
        assertEquals(BehaviorStatus.SUCCESS, loop.run());
        assertEquals(BehaviorStatus.SUCCESS, loop.run());
        assertEquals(1, child.runs); assertEquals(3, child.resets);
    }

    @Test void resetDuringRunOrCleanupCannotRestartTheOldLoop() {
        for (boolean duringRun : new boolean[] { false, true }) {
            var child = new Child(); var checks = new AtomicInteger();
            var loop = new Loop(child, () -> { checks.incrementAndGet(); return true; });
            if (duringRun) child.onRun = loop::reset;
            else child.onReset = loop::reset;
            assertTrue(loop.shouldExecute()); assertEquals(BehaviorStatus.RUNNING, loop.run());
            assertEquals(BehaviorStatus.SUCCESS, loop.run());
            assertEquals(1, child.starts); assertEquals(1, child.runs); assertEquals(1, child.resets);
            assertEquals(0, checks.get());
        }
    }

    @Test void conditionResetCannotStartAnotherIteration() {
        var child = new Child(); Loop[] holder = new Loop[1];
        holder[0] = new Loop(child, () -> { holder[0].reset(); return true; });
        assertTrue(holder[0].shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, holder[0].run());
        assertEquals(BehaviorStatus.SUCCESS, holder[0].run());
        assertEquals(1, child.starts); assertEquals(1, child.runs); assertEquals(1, child.resets);
    }

    @Test void replacementActivationSurvivesStaleCleanupAndNestedRun() {
        var child = new Child(); var loop = new Loop(child, () -> false);
        assertTrue(loop.shouldExecute());
        child.onReset = () -> {
            child.onReset = () -> { };
            assertTrue(loop.shouldExecute());
            assertEquals(BehaviorStatus.RUNNING, loop.run());
        };
        assertEquals(BehaviorStatus.RUNNING, loop.run());
        assertEquals(1, child.runs); assertEquals(2, child.starts);
        assertEquals(BehaviorStatus.SUCCESS, loop.run());
        assertEquals(2, child.runs); assertEquals(2, child.resets);
    }

    @Test void callbackFailuresStopOwnedWorkAndPreserveCleanupFailure() {
        for (String phase : new String[] { "start", "run", "condition" }) {
            var child = new Child(); var primary = new IllegalStateException(phase);
            var cleanup = new IllegalArgumentException("cleanup");
            var loop = new Loop(child, () -> { throw primary; });
            if (phase.equals("start")) child.onStart = () -> { throw primary; };
            else assertTrue(loop.shouldExecute());
            if (phase.equals("run")) child.onRun = () -> { throw primary; };
            if (!phase.equals("condition")) child.onReset = () -> { throw cleanup; };
            Throwable caught = assertThrows(IllegalStateException.class, () -> {
                if (phase.equals("start")) loop.shouldExecute(); else loop.run();
            });
            assertSame(primary, caught);
            if (!phase.equals("condition")) assertArrayEquals(new Throwable[] { cleanup }, caught.getSuppressed());
            assertEquals(BehaviorStatus.FAILURE, loop.run());
            loop.reset(); assertEquals(1, child.resets);
        }
    }
}
