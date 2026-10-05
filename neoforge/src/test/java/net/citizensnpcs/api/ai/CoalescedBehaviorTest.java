package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class CoalescedBehaviorTest {
    @Test void resetAttemptsEveryChildAndRetainsAllErrors() {
        AtomicInteger resets = new AtomicInteger();
        List<Behavior> children = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int index = i;
            children.add(new Behavior() {
                public boolean shouldExecute() { return true; }
                public BehaviorStatus run() { return BehaviorStatus.SUCCESS; }
                public void reset() { resets.incrementAndGet(); throw new IllegalStateException("child " + index); }
            });
        }
        var failure = assertThrows(IllegalStateException.class, () -> new CoalescedBehavior(children).reset());
        assertEquals("child 0", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(3, resets.get());
    }

    @Test void callerListMutationCannotChangeTheConstructedGroup() {
        AtomicInteger runs = new AtomicInteger();
        List<Behavior> children = new ArrayList<>();
        children.add((InstantBehavior) () -> { runs.incrementAndGet(); children.clear(); return BehaviorStatus.SUCCESS; });
        children.add((InstantBehavior) () -> { runs.incrementAndGet(); return BehaviorStatus.SUCCESS; });
        var grouped = new CoalescedBehavior(children);
        assertEquals(BehaviorStatus.SUCCESS, grouped.run());
        assertEquals(2, runs.get());
        assertTrue(children.isEmpty());
        assertEquals(BehaviorStatus.SUCCESS, grouped.run());
        assertEquals(4, runs.get());
    }
}
