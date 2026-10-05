package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class SelectorTerminalResetTest {
    @Test void terminalResetFailureDoesNotRetainCompletedExecution() {
        for (var status : new BehaviorStatus[] {BehaviorStatus.SUCCESS, BehaviorStatus.FAILURE, BehaviorStatus.RESET_AND_REMOVE}) {
            int[] calls = {0, 0};
            Behavior child = new Behavior() {
                public boolean shouldExecute() { return true; }
                public BehaviorStatus run() { calls[0]++; return status; }
                public void reset() { calls[1]++; throw new IllegalStateException("reset"); }
            };
            var selector = Selector.selecting(child).build();
            assertThrows(IllegalStateException.class, selector::run);
            assertDoesNotThrow(() -> selector.removeBehavior(child));
            assertEquals(BehaviorStatus.FAILURE, selector.run());
            assertEquals(1, calls[0]); assertEquals(1, calls[1]);
        }
    }

    @Test void resetCallbackCanStartAReplacementWithoutItBeingCleared() {
        final Selector[] selector = new Selector[1];
        int[] nextRuns = {0};
        Behavior next = new Behavior() {
            public boolean shouldExecute() { return true; }
            public BehaviorStatus run() { nextRuns[0]++; return BehaviorStatus.RUNNING; }
            public void reset() { fail("Replacement was reset by the old completion"); }
        };
        Behavior previous = new Behavior() {
            public boolean shouldExecute() { return true; }
            public BehaviorStatus run() { return BehaviorStatus.RESET_AND_REMOVE; }
            public void reset() {
                selector[0].addBehavior(next);
                assertEquals(BehaviorStatus.RUNNING, selector[0].run());
            }
        };
        selector[0] = Selector.selecting(previous).build();
        assertEquals(BehaviorStatus.RUNNING, selector[0].run());
        assertEquals(BehaviorStatus.RUNNING, selector[0].run());
        assertEquals(2, nextRuns[0]);
    }
}
