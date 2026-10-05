package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.ai.tree.ParallelBehavior;

class ParallelBehaviorLifecycleTest {
    private static class Parallel implements Behavior, ParallelBehavior {
        int runs, resets;
        Runnable callback = () -> {};
        BehaviorStatus status = BehaviorStatus.RUNNING;
        @Override public boolean shouldExecute() { return true; }
        @Override public BehaviorStatus run() { runs++; callback.run(); return status; }
        @Override public void reset() { resets++; }
    }

    @Test void dynamicallyAddedParallelBehaviorRunsWithoutSerialChildrenAndClears() {
        var controller = new SimpleBehaviorController();
        var child = new Parallel(); controller.addBehavior(child);
        controller.run(); controller.run();
        assertEquals(2, child.runs);
        assertEquals(0, child.resets);
        controller.clear(); controller.run();
        assertEquals(2, child.runs);
        assertEquals(1, child.resets);
    }

    @Test void resetAndRemoveDoesNotRestartOnTheNextTick() {
        var controller = new SimpleBehaviorController();
        var child = new Parallel(); child.status = BehaviorStatus.RESET_AND_REMOVE;
        controller.addBehavior(child); controller.run(); controller.run();
        assertEquals(1, child.runs);
        assertEquals(1, child.resets);
    }

    @Test void selfRemovalDoesNotBreakIterationOrResetTwice() {
        var controller = new SimpleBehaviorController();
        var child = new Parallel(); child.status = BehaviorStatus.SUCCESS;
        child.callback = () -> controller.removeBehavior(child);
        var other = new Parallel();
        controller.addBehavior(child); controller.addBehavior(other);
        assertDoesNotThrow(controller::run);
        controller.run();
        assertEquals(1, child.runs);
        assertEquals(1, child.resets);
        assertEquals(2, other.runs);
    }

    @Test void callbackAddedParallelChildStartsOnALaterTick() {
        var controller = new SimpleBehaviorController();
        var parent = new Parallel(); var added = new Parallel();
        parent.callback = () -> {
            if (parent.runs == 1) controller.addBehavior(added);
        };
        controller.addBehavior(parent); controller.run();
        assertEquals(0, added.runs);
        controller.run();
        assertEquals(1, added.runs);
    }
}
