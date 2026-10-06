# Validated moveto lifecycle

`/npc moveto` previously spawned an unspawned NPC before parsing its destination,
split native dimension names at every colon, accepted non-finite coordinates, overwrote
requested rotation by facing its own destination, and reported success unconditionally.

The command now validates ownership, location, numeric values, flags and dimension
identity before changing NPC state. Space-separated and colon-separated coordinates
preserve a namespaced dimension suffix. Dimension lookup uses the existing strict
resolver, so a missing namespace cannot fall back to another world's matching path.

Unspawned NPCs receive the teleport event before spawning directly at the validated
destination. Cancellation leaves them unspawned. Ownership is rechecked after the
event; destination edits are validated before spawning. Spawned NPCs use the normal
completion-aware teleport API. Both paths report the confirmed resulting location only
after completion, and preserve yaw/pitch through transport. Existing positional versus
flag precedence and the moveto permission remain unchanged.

The native relocation fixture exercises ordinary/player-shaped NPCs with normal/packet
transport from a named registry. It verifies no spawn or location mutation for invalid
input, cancellation without success feedback, requested rotation, and cross-world
transport through native dimension IDs. Physical-client rendering and arbitrary external
provider callbacks remain separate acceptance. Interactions remains a separate JAR.
