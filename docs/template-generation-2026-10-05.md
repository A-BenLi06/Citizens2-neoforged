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
