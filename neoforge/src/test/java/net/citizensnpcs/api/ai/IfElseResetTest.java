package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class IfElseResetTest {
    @Test void throwingBranchResetReleasesOwnershipAndAllowsOtherBranch() {
        var condition = new AtomicBoolean(true);
        var resets = new AtomicInteger();
        Behavior failing = new Behavior() {
            public boolean shouldExecute() { return true; }
            public BehaviorStatus run() { return BehaviorStatus.RUNNING; }
            public void reset() { resets.incrementAndGet(); throw new IllegalStateException("reset"); }
        };
        var elseRuns = new AtomicInteger();
        Behavior other = (InstantBehavior) () -> { elseRuns.incrementAndGet(); return BehaviorStatus.SUCCESS; };
        var branch = IfElse.create(condition::get, failing, other);
        assertTrue(branch.shouldExecute()); branch.run();
        assertThrows(IllegalStateException.class, branch::reset);
        assertDoesNotThrow(branch::reset);
        assertEquals(1, resets.get());
        condition.set(false);
        assertTrue(branch.shouldExecute());
        assertEquals(BehaviorStatus.SUCCESS, branch.run());
        assertEquals(1, elseRuns.get());
    }
}
