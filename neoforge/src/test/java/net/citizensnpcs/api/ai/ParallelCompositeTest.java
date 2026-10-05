package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class ParallelCompositeTest {
    @Test void callbackClearStopsOldSiblingsAndAllowsANewCycle() {
        final ParallelComposite[] owner = new ParallelComposite[1];
        var next = new Child();
        var first = new Child() {
            @Override public BehaviorStatus run() {
                calls++;
                owner[0].clearBehaviors();
                owner[0].addBehavior(new ParallelBehaviorWrapper(next));
                return BehaviorStatus.SUCCESS;
            }
        };
        var skipped = new Child();
        owner[0] = new ParallelComposite(List.of(new ParallelBehaviorWrapper(first), new ParallelBehaviorWrapper(skipped)));
        assertEquals(BehaviorStatus.SUCCESS, owner[0].run());
        assertEquals(1, first.resets); assertEquals(1, skipped.resets);
        assertEquals(0, skipped.calls); assertEquals(0, next.calls);
        assertEquals(BehaviorStatus.RUNNING, owner[0].run());
        assertEquals(1, next.calls);
    }

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
