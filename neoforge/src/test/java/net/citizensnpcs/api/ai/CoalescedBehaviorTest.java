package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class CoalescedBehaviorTest {
    @Test void resetFromAnActionStopsRemainingEffectsUntilNextRun() {
        var first = new AtomicInteger(); var second = new AtomicInteger();
        CoalescedBehavior[] group = new CoalescedBehavior[1];
        group[0] = new CoalescedBehavior(List.of(
                (InstantBehavior) () -> {
                    if (first.incrementAndGet() == 1) group[0].reset();
                    return BehaviorStatus.SUCCESS;
                },
                (InstantBehavior) () -> { second.incrementAndGet(); return BehaviorStatus.SUCCESS; }));
        assertEquals(BehaviorStatus.RUNNING, group[0].run()); assertEquals(0, second.get());
        assertEquals(BehaviorStatus.SUCCESS, group[0].run());
        assertEquals(2, first.get()); assertEquals(1, second.get());
    }

    @Test void recursiveRunDoesNotRepeatActionsAndExceptionReleasesGuard() {
        var calls = new AtomicInteger(); CoalescedBehavior[] group = new CoalescedBehavior[1];
        var failure = new IllegalStateException("action");
        group[0] = new CoalescedBehavior(List.of((InstantBehavior) () -> {
            assertEquals(BehaviorStatus.RUNNING, group[0].run());
            if (calls.incrementAndGet() == 1) throw failure;
            return BehaviorStatus.SUCCESS;
        }));
        assertSame(failure, assertThrows(IllegalStateException.class, group[0]::run));
        assertEquals(BehaviorStatus.SUCCESS, group[0].run()); assertEquals(2, calls.get());
    }

    @Test void resetDuringAdmissionRejectsTheStaleResult() {
        CoalescedBehavior[] group = new CoalescedBehavior[1];
        group[0] = new CoalescedBehavior(List.of(new InstantBehavior() {
            public boolean shouldExecute() { group[0].reset(); return true; }
            public BehaviorStatus run() { fail("stale admission must not execute"); return BehaviorStatus.SUCCESS; }
        }));
        assertFalse(group[0].shouldExecute());
    }

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
