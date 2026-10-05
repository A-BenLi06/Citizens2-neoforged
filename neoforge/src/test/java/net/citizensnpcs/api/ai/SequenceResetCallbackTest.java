package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class SequenceResetCallbackTest {
    private static void exercise(BehaviorStatus completion, boolean admitted) {
        final Sequence[] owner = new Sequence[1];
        int[] runs = {0};
        Behavior next = new Behavior() {
            public boolean shouldExecute() { return true; }
            public BehaviorStatus run() { runs[0]++; return BehaviorStatus.RUNNING; }
            public void reset() { fail("Callback-started successor was reset"); }
        };
        Behavior previous = new Behavior() {
            public boolean shouldExecute() { return admitted; }
            public BehaviorStatus run() { return completion; }
            public void reset() { assertEquals(BehaviorStatus.RUNNING, owner[0].run()); }
        };
        Behavior tail = new Behavior() {
            public boolean shouldExecute() { fail("Successor was skipped"); return true; }
            public BehaviorStatus run() { return BehaviorStatus.SUCCESS; }
            public void reset() { }
        };
        owner[0] = Sequence.createSequence(previous, next, tail);
        assertEquals(BehaviorStatus.RUNNING, owner[0].run());
        assertEquals(BehaviorStatus.RUNNING, owner[0].run());
        assertEquals(2, runs[0]);
    }

    @Test void terminalResetDoesNotAdvancePastACallbackStartedSuccessor() {
        for (var status : new BehaviorStatus[] {BehaviorStatus.SUCCESS, BehaviorStatus.FAILURE, BehaviorStatus.RESET_AND_REMOVE})
            exercise(status, true);
    }

    @Test void rejectedAdmissionDoesNotResetACallbackStartedSuccessor() {
        exercise(BehaviorStatus.FAILURE, false);
    }
}
