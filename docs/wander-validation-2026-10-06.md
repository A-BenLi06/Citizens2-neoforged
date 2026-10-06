# Validate wander commands before replacing routes

`/npc wander` previously attached Waypoints and switched to the wander provider before
checking its arguments. Invalid input could consequently replace a valid route or discard
the retained data for an unavailable one.

Each subcommand now prepares its full operation first. Coordinates/worlds, arity,
booleans, non-negative ranges and bounded non-negative durations are checked before
trait attachment or provider switching. Unknown flags are rejected. Successful switching
and current NPC/trait ownership are checked before applying the operation. The bare
toggle reports success only after the provider switch succeeds.

Bare delay numbers retain their existing seconds interpretation; explicit tick suffixes
remain supported. Usage now describes this argument as a duration. The native lifecycle
fixture tests invalid inputs with no trait, with a linear route and with unavailable saved
data, followed by successful wander configuration. Custom callback side effects and
physical-client/provider acceptance remain separate work. Interactions remains independent.
