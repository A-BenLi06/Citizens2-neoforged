# Undo history callback boundaries

Each recorded operation now has its own entry identity. Recording the same callback
object twice therefore creates two distinct history entries. Successful undo removes
only its original entry, preserving entries added during callbacks.

Single and bulk undo reserve the sender's history while executing callbacks. A nested
undo for that sender fails before executing a record again, and exceptions always
release the reservation for retry.

`/npc undo all` captures the entries present when the command begins and processes
them newest first. Entries explicitly discarded by callbacks are skipped. Newly
recorded operations remain available for a subsequent command, so a callback that
adds another undo operation cannot cause the current command to run indefinitely.

Bulk undo remains sequential: a failure stops the command, completed entries stay
removed, and the failed/older entries remain available. It does not roll back earlier
successful undo operations or arbitrary external callback side effects.

Six history tests cover nested rejection, failed retry, callback-added history,
re-adding the identical callback object during a batch, failure after partial batch
success, and discarding pending entries. All 360 ordinary tests pass with no failures,
errors or skips. Native command regression evidence is recorded in walkthrough.md.
