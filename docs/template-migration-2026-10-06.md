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

Migration validates the derived template name before creating its destination
namespace or writing/archiving files. Invalid resource paths and empty filename stems
are rejected while preserving the source. The empty-stem check is explicit because
ResourceLocation itself permits an empty path. A test verifies both cases have no
destination/archive side effects.

Legacy contents are now validated and normalized by the same YAML replacement
persister used for ordinary templates, before creating the destination directory.
Invalid override/replacement types preserve source files, while accepted quoted
Boolean values normalize consistently. Two file tests verify these paths.

Retry comparison now normalizes the destination's known YAML replacement options
through the same persister, allowing equivalent quoted/Boolean values without
rewriting destination bytes. Extra destination fields/actions remain in the compared
structure and still cause a conflict. Two file tests verify equivalence and conservative
conflict preservation.

Additional retry tests verify omitted default options are accepted without rewriting
the destination, while extra fields inside yaml_replace still cause a conservative
conflict and preserve both files. No additional production changes were required.
