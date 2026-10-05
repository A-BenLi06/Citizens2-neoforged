package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class TimeoutDecoratorTest {
    private static class Child implements Behavior {
        int calls;
        boolean failReset;
        BehaviorStatus status = BehaviorStatus.RUNNING;
        public boolean shouldExecute() { return true; }
        public BehaviorStatus run() { calls++; return status; }
        public void reset() { if (failReset) throw new IllegalStateException("reset"); }
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
}
