# Native world IDs in teleport trigger input

The waypoint teleport prompt previously split every colon, so a native world ID such as
`minecraft:the_nether` made an otherwise valid `world:x:y:z` input fail. It also lacked
the editor's inline trigger creation path.

Coordinates are now parsed from the final three fields, retaining the entire world ID.
The existing integer coordinate contract is preserved. Missing, malformed and overflowing
coordinates are rejected before session data changes. World lookup uses the shared strict
namespace/ambiguity rules. Valid inputs always create a TeleportTrigger object.

The inline `teleport world:x:y:z` and `teleport here` forms share the same creation logic.
Invalid inline input falls back to the dedicated prompt as the trigger editor expects.
The `back` and ordinary `here` prompt controls retain their behavior.

Parser tests cover native IDs, mod dimension paths, signed coordinates, whitespace and
invalid numbers. Native chat-session tests verify retained data after rejection, successful
prompt completion, inline forms and actual execution into the requested dimension/position.
Physical-client interaction and external provider combinations remain separate acceptance.
Interactions remains an independent mod/JAR; this is Citizens waypoint functionality.
