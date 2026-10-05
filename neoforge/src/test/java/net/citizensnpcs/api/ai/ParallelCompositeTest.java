package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class ParallelCompositeTest {
    private static class Child implements Behavior {
        int calls, resets;
        BehaviorStatus status = BehaviorStatus.RUNNING;
        @Override public boolean shouldExecute() { return true; }
        @Override public BehaviorStatus run() { calls++; return status; }
        @Override public void reset() { resets++; }
    }

    @Test void startsChildrenAndWaitsWithoutRestartingFinishedOnes() {
        var fast = new Child(); fast.status = BehaviorStatus.SUCCESS;
        var slow = new Child();
        var composite = new ParallelComposite(List.of(new ParallelBehaviorWrapper(fast), new ParallelBehaviorWrapper(slow)));
        assertEquals(BehaviorStatus.RUNNING, composite.run());
        assertEquals(BehaviorStatus.RUNNING, composite.run());
        assertEquals(1, fast.calls); assertEquals(1, fast.resets);
        assertEquals(2, slow.calls);
        slow.status = BehaviorStatus.FAILURE;
        assertEquals(BehaviorStatus.SUCCESS, composite.run());
        assertEquals(1, slow.resets);
        assertEquals(BehaviorStatus.SUCCESS, composite.run());
        assertEquals(3, slow.calls);
    }

    @Test void resetAllowsAnotherCycleButRemovedChildrenStayRemoved() {
        var recurring = new Child(); recurring.status = BehaviorStatus.SUCCESS;
        var removed = new Child(); removed.status = BehaviorStatus.RESET_AND_REMOVE;
        var composite = new ParallelComposite(List.of(new ParallelBehaviorWrapper(recurring), new ParallelBehaviorWrapper(removed)));
        assertEquals(BehaviorStatus.SUCCESS, composite.run());
        composite.reset();
        assertEquals(BehaviorStatus.SUCCESS, composite.run());
        assertEquals(2, recurring.calls);
        assertEquals(1, removed.calls);
    }

    @Test void emptyCompositeCompletesImmediately() {
        assertEquals(BehaviorStatus.SUCCESS, new ParallelComposite(List.of()).run());
    }
}
