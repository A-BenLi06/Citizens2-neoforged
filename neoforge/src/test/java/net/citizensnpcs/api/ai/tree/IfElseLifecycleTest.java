package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class IfElseLifecycleTest {
    private static class Child implements Behavior {
        int starts, resets, runs;
        boolean ready = true;
        Runnable starting = () -> { }, resetting = () -> { }, running = () -> { };
        public boolean shouldExecute() { starts++; starting.run(); return ready; }
        public BehaviorStatus run() { runs++; running.run(); return BehaviorStatus.SUCCESS; }
        public void reset() { resets++; resetting.run(); }
    }

    @Test void switchingBranchesReleasesThePreviousBranch() {
        var condition = new AtomicBoolean(true); var first = new Child(); var second = new Child();
        var branch = new IfElse(condition::get, first, second);
        assertTrue(branch.shouldExecute()); condition.set(false); assertTrue(branch.shouldExecute());
        assertEquals(1, first.resets); assertEquals(1, second.starts);
        branch.reset(); assertEquals(1, second.resets);
    }

    @Test void rejectedAndThrowingStartsReleaseTheirBranch() {
        var child = new Child(); child.ready = false;
        var branch = new IfElse(() -> true, child, null);
        assertFalse(branch.shouldExecute()); assertEquals(1, child.resets);
        assertEquals(BehaviorStatus.FAILURE, branch.run());
        var failure = new IllegalStateException("start failed");
        child.starting = () -> { throw failure; };
        child.resetting = () -> { throw new IllegalStateException("reset failed"); };
        assertSame(failure, assertThrows(IllegalStateException.class, branch::shouldExecute));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(BehaviorStatus.FAILURE, branch.run());
    }

    @Test void cleanupReentryPreservesTheNewerBranch() {
        var condition = new AtomicBoolean(true); var first = new Child(); var second = new Child();
        var branch = new IfElse(condition::get, first, second);
        assertTrue(branch.shouldExecute());
        first.resetting = () -> { condition.set(false); assertTrue(branch.shouldExecute()); };
        assertFalse(branch.shouldExecute());
        assertEquals(BehaviorStatus.SUCCESS, branch.run());
        assertEquals(0, first.runs); assertEquals(1, second.runs);
    }

    @Test void resetCallbacksCannotLeaveAStaleSelectedBranch() {
        var child = new Child(); var branch = new IfElse(() -> true, child, null);
        child.starting = branch::reset;
        assertFalse(branch.shouldExecute());
        assertEquals(BehaviorStatus.FAILURE, branch.run());
        child.starting = () -> { }; child.running = branch::reset;
        assertTrue(branch.shouldExecute());
        assertEquals(BehaviorStatus.RUNNING, branch.run());
        assertEquals(BehaviorStatus.FAILURE, branch.run());
    }
}
