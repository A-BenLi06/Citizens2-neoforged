package net.citizensnpcs.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.tree.*;

class SequenceRemovalTest {
    private static class Step implements Behavior {
        int runs, resets;
        BehaviorStatus status = BehaviorStatus.RUNNING;
        @Override public boolean shouldExecute() { return true; }
        @Override public BehaviorStatus run() { runs++; return status; }
        @Override public void reset() { resets++; }
    }

    @Test void terminalRemovalResetsTheRemovedChildAndReachesTheNextOne() {
        var first = new Step(); first.status = BehaviorStatus.RESET_AND_REMOVE;
        var second = new Step();
        var sequence = Sequence.createSequence(first, second);
        assertEquals(BehaviorStatus.RUNNING, sequence.run());
        sequence.run();
        assertEquals(1, first.runs); assertEquals(1, first.resets);
        assertEquals(1, second.runs);
        assertFalse(sequence.getBehaviors().contains(first));
    }

    @Test void removingAnEarlierChildDoesNotSkipTheSuccessor() {
        var first = new Step(); first.status = BehaviorStatus.SUCCESS;
        var active = new Step(); var last = new Step();
        var sequence = Sequence.createSequence(first, active, last);
        sequence.run(); sequence.run();
        sequence.removeBehavior(first);
        sequence.removeBehavior(active);
        sequence.run();
        assertEquals(1, active.runs); assertEquals(1, active.resets);
        assertEquals(1, last.runs);
    }

    @Test void admissionCallbackMayRemoveItsOwnNode() {
        final Sequence[] owner = new Sequence[1];
        var removed = new Step() {
            @Override public boolean shouldExecute() { owner[0].removeBehavior(this); return true; }
        };
        var next = new Step(); owner[0] = Sequence.createSequence(removed, next);
        assertDoesNotThrow(owner[0]::run); owner[0].run();
        assertEquals(0, removed.runs); assertEquals(1, removed.resets);
        assertEquals(1, next.runs);
    }

    @Test void throwingResetDoesNotLeaveTheOldExecutionReference() {
        var step = new Step() {
            @Override public void reset() { resets++; throw new IllegalStateException("reset"); }
        };
        var sequence = Sequence.createSequence(step); sequence.run();
        assertThrows(IllegalStateException.class, sequence::clearBehaviors);
        assertDoesNotThrow(sequence::reset);
        assertEquals(BehaviorStatus.SUCCESS, sequence.run());
        assertEquals(1, step.runs); assertEquals(1, step.resets);
    }
}
