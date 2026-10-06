# Template generation and overwrite

Overwriting a generated template previously removed only the fully qualified index
entry, left the old bare-name entry behind, and reloaded every template in the file.
Sibling templates could then be reported as duplicates and stop the reload midway.

Generation now creates a detached snapshot, removes the prior replacement subtree,
omits the NPC UUID, validates the candidate action, and saves before replacing either
lookup index. Only the generated entry changes. Existing templates retain their
source file and literal YAML key, including case and dots; new keys use templates.yml
in their namespace. This avoids creating a duplicate definition in another file.

Three file-based tests cover repeated generation with a sibling template, both
indexes pointing to the same replacement, source/key preservation, removal of stale
snapshot fields/UUID, and unchanged disk/index state after a snapshot exception.
NPC serialization is supplied through an interface test double; these tests do not
claim live command or client coverage.

The native creation fixture now generates a sibling template, overwrites the original
with a different age value, verifies name/key index identity, then creates an NPC
through the real template command path and checks the updated trait value. Both
NeoForge versions pass 306 creation checks. Physical-client behavior is not asserted.

YAML replacement application captures its target through saveSnapshot instead of
ordinary persistence. A partial template can therefore preserve the name, owner
and save policy of an unsaved/temporary target. A focused test covers this contract
with an NPC interface double that intentionally omits ordinary saves.

Template collection queries now return immutable snapshots instead of exposing the
internal index views. External clear/remove operations cannot corrupt only one index,
and previously obtained query results remain stable when a template is replaced.
Two tests cover mutation rejection and snapshot stability; command callers were
checked to use these collections read-only.

Namespace existence queries now use the same Locale.ROOT case normalization as loaded
keys and bare-name lookup. A test switches the default locale to Turkish and verifies
mixed-case namespace/name lookup, absent namespaces and null input returning false.

Normal template loading and generation reject empty template names explicitly, since
ResourceLocation permits an empty path. File tests verify rejection preserves the
input; generation tests verify the NPC snapshot and namespace directory are untouched.

The generate command rejects empty paths with the existing localized template error
before accessing the registry/NPC. Native dispatcher coverage checks that rendered
error and verifies no namespace directory is created. The expected message is rendered
through the same text pipeline as Brigadier rather than compared to markup source.
