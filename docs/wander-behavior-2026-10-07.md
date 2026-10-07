# Activate and release behavior-tree wandering

The wander behavior previously built a WanderGoal and called run without calling its
shouldExecute method, leaving its destination unset. Reset discarded the goal without
stopping it. The wrapper now performs goal admission during activation, delegates running
only after successful admission, and pauses its previous goal before releasing ownership.
Generation checks preserve newer state across callbacks. WanderGoal.pause can also cancel
its own pathfinding request before destination publication finishes.

Radius values must be exact non-negative integers with representable random-search widths.
Omitted pathfinding uses the builder's normal pathfinding default. Explicit positional or
named false values select direct movement; boolean and numeric expression results are
handled according to their types rather than treating every nonempty string as true.

Unit tests reject invalid radius/pathfinding input before world or navigator queries.
Native ordinary/packet NPC cases verify startup, running, owned reset, foreign-request
preservation, begin-callback reset and direct-movement admission. Physical-client movement
and arbitrary provider combinations remain separate acceptance. Interactions stays in its
independent NeoForge JAR.
