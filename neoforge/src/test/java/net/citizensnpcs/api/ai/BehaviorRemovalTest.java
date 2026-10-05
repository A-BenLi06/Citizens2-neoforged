package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.Behavior;
import net.citizensnpcs.api.ai.tree.BehaviorStatus;

class BehaviorRemovalTest {
    @Test void resetAttemptsParallelAndSelectedChildrenEvenWhenEachThrows() {
        var first = new Running(); first.failReset = true;
        var second = new Running(); second.failReset = true;
        var selected = new Running(); selected.failReset = true;
        var selector = net.citizensnpcs.api.ai.tree.Selector.selecting(
                new net.citizensnpcs.api.ai.tree.ParallelBehaviorWrapper(first),
                new net.citizensnpcs.api.ai.tree.ParallelBehaviorWrapper(second), selected).build();
        assertEquals(BehaviorStatus.RUNNING, selector.run());
        var failure = assertThrows(IllegalStateException.class, selector::reset);
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(1, first.resets);
        assertEquals(1, second.resets);
        assertEquals(1, selected.resets);
        assertDoesNotThrow(selector::reset);
    }

    @Test void clearFailureDoesNotLeaveOldBehaviorExecutable() {
        var controller = new SimpleBehaviorController();
        var previous = new Running(); previous.failReset = true;
        controller.addBehavior(previous); controller.run();
        assertThrows(IllegalStateException.class, controller::clear);
        assertDoesNotThrow(controller::run);
        assertDoesNotThrow(controller::clear);
        assertEquals(1, previous.runs);
        assertEquals(1, previous.resets);
    }

    @Test void clearPreservesBehaviorAddedByAResetCallback() {
        var controller = new SimpleBehaviorController();
        var next = new Running();
        var previous = new Running() {
            @Override public void reset() { resets++; controller.addBehavior(next); }
        };
        controller.addBehavior(previous); controller.run();
        controller.clear(); controller.run();
        assertEquals(1, previous.runs);
        assertEquals(1, previous.resets);
        assertEquals(1, next.runs);
    }

    @Test void behaviorCanRemoveItselfFromAdmissionOrExecution() {
        for (boolean duringAdmission : new boolean[] { true, false }) {
            var controller = new SimpleBehaviorController();
            var removed = new Running() {
                @Override public boolean shouldExecute() {
                    if (duringAdmission) controller.removeBehavior(this);
                    return true;
                }
                @Override public BehaviorStatus run() {
                    runs++;
                    controller.removeBehavior(this);
                    return BehaviorStatus.SUCCESS;
                }
            };
            controller.addBehavior(removed);
            assertDoesNotThrow(controller::run);
            assertDoesNotThrow(controller::run);
            assertEquals(duringAdmission ? 0 : 1, removed.runs);
            assertEquals(1, removed.resets);
        }
    }

    private static class Running implements Behavior {
        int runs, resets;
        boolean failReset;
        @Override public boolean shouldExecute() { return true; }
        @Override public BehaviorStatus run() { runs++; return BehaviorStatus.RUNNING; }
        @Override public void reset() { resets++; if (failReset) throw new IllegalStateException("reset failure"); }
    }

    @Test void removedRunningBehaviorCannotRunAgain() {
        var controller = new SimpleBehaviorController();
        var removed = new Running(); controller.addBehavior(removed); controller.run();
        controller.removeBehavior(removed);
        controller.run();
        assertEquals(1, removed.runs);
        assertEquals(1, removed.resets);
        var next = new Running(); controller.addBehavior(next); controller.run();
        assertEquals(1, next.runs);
    }

    @Test void throwingResetCannotRetainRemovedExecution() {
        var controller = new SimpleBehaviorController();
        var removed = new Running(); removed.failReset = true;
        controller.addBehavior(removed); controller.run();
        assertThrows(IllegalStateException.class, () -> controller.removeBehavior(removed));
        assertDoesNotThrow(controller::run);
        assertEquals(1, removed.runs);
        assertEquals(1, removed.resets);
    }
}
