package net.citizensnpcs.commands.history;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.command.exception.CommandException;

class CommandHistoryTest {
    @Test
    void bulkUndoLeavesReaddedSameOperationForTheNextCommand() throws CommandException {
        CommandHistory history = new CommandHistory(null);
        AtomicInteger executions = new AtomicInteger();
        CommandHistoryItem[] repeating = new CommandHistoryItem[1];
        repeating[0] = (sender, selector) -> {
            executions.incrementAndGet();
            history.add(sender, repeating[0]);
            assertThrows(CommandException.class, () -> history.undoAll(sender));
        };
        history.add(null, repeating[0]);
        history.undoAll(null);
        assertEquals(1, executions.get());
        history.undoAll(null);
        assertEquals(2, executions.get());
    }

    @Test
    void bulkFailureKeepsFailedAndOlderEntriesWithoutRepeatingSuccess() throws CommandException {
        CommandHistory history = new CommandHistory(null);
        var order = new java.util.ArrayList<String>();
        AtomicInteger attempts = new AtomicInteger();
        history.add(null, (sender, selector) -> order.add("old"));
        history.add(null, (sender, selector) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("retry");
            order.add("middle");
        });
        history.add(null, (sender, selector) -> order.add("new"));
        assertThrows(IllegalStateException.class, () -> history.undoAll(null));
        assertEquals(java.util.List.of("new"), order);
        history.undoAll(null);
        assertEquals(java.util.List.of("new", "middle", "old"), order);
        assertFalse(history.undo(null));
    }

    @Test
    void bulkUndoSkipsEntriesExplicitlyDiscardedByCallbacks() throws CommandException {
        CommandHistory history = new CommandHistory(null);
        CommandHistoryItem discarded = (sender, selector) -> fail("Discarded entry executed");
        history.add(null, discarded);
        history.add(null, (sender, selector) -> history.discard(sender, discarded));
        history.undoAll(null);
        assertFalse(history.undo(null));
    }

    @Test
    void nestedUndoIsRejectedBeforeExecutingTheRecordAgain() throws CommandException {
        CommandHistory history = new CommandHistory(null);
        AtomicInteger executions = new AtomicInteger();
        history.add(null, (sender, selector) -> {
            executions.incrementAndGet();
            assertThrows(CommandException.class, () -> history.undo(sender));
        });
        assertTrue(history.undo(null));
        assertEquals(1, executions.get());
        assertFalse(history.undo(null));
    }

    @Test
    void failedRecordAndOlderRecordsRemainAvailableForRetry() throws CommandException {
        CommandHistory history = new CommandHistory(null);
        AtomicInteger older = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        history.add(null, (sender, selector) -> older.incrementAndGet());
        history.add(null, (sender, selector) -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("retry");
        });
        assertThrows(IllegalStateException.class, () -> history.undo(null));
        assertEquals(0, older.get());
        assertTrue(history.undo(null));
        assertEquals(2, attempts.get());
        assertTrue(history.undo(null));
        assertEquals(1, older.get());
        assertFalse(history.undo(null));
    }

    @Test
    void callbackHistorySurvivesCompletionOfTheOriginalUndo() throws CommandException {
        CommandHistory history = new CommandHistory(null);
        AtomicInteger callback = new AtomicInteger();
        history.add(null, (sender, selector) -> history.add(sender, (laterSender, laterSelector) -> callback.incrementAndGet()));
        assertTrue(history.undo(null));
        assertEquals(0, callback.get());
        assertTrue(history.undo(null));
        assertEquals(1, callback.get());
        assertFalse(history.undo(null));
    }
}
