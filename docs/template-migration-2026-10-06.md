# Preserve files during legacy template migration

Legacy migration now checks for an existing archive before writing a destination.
Conflicting destination definitions are rejected, while identical definitions allow
an interrupted migration to finish without rewriting destination formatting. Template
key matching respects the registry's case-insensitive identity.

Migrated keys are written as literal map keys, preserving dots. Only the final .yml
extension is removed from the source filename. Archiving uses a non-replacing move,
so an existing archive is not overwritten.

Four filesystem tests cover destination conflict preservation, identical retry,
archive collision and dotted/internal-extension filenames. Migration still has two
filesystem steps; a failed archive move may leave an identical destination ready for
retry, and arbitrary external concurrent writers are not transactionally rolled back.
