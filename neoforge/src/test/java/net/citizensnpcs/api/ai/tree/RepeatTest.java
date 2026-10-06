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
        public boolean shouldExecute() { starts++; return ready; }
        public BehaviorStatus run() { runs++; return ++phase == 1 ? BehaviorStatus.RUNNING : result; }
        public void reset() { resets++; phase = 0; afterReset.run(); }
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
