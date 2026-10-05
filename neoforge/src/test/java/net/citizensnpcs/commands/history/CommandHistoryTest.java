package net.citizensnpcs.commands.history;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.command.exception.CommandException;

class CommandHistoryTest {
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
