# Restore behavior memory before constructing the tree

BehaviorTrait used to construct a saved tree before loading its blackboard. A
behavior factory reading its context during construction therefore captured empty
values on first restoration or stale values when replacing an existing tree.
An unreadable memory section could also fail after the tree had already changed.

Loading both `tree` and `memory` now prepares the saved definition and a separate
Memory instance before invoking the parser. Factories receive that prepared memory.
The trait publishes the replacement root and memory after construction and
controller installation succeed, then retains the prepared definition for saving.
Memory-read and factory-construction failures preserve the old live root, memory
identity and contents, and cached definition. Writes to the candidate's memory do
not leak into the old blackboard when its factory rejects construction.

| Load or lifecycle operation | Memory and behavior contract |
|---|---|
| Both tree and memory | Construct against incoming values; publish a new Memory instance on success. |
| Memory only | Load into the existing Memory instance without rebuilding the live tree. |
| Tree only | Build against the current Memory instance; create one if needed. |
| Explicit empty tree with memory | Remove execution and publish the restored memory. |
| Despawn and respawn | Rebuild the cached tree against the same current Memory instance. |
| Explicit definition application | Preserve the existing blackboard and strict parsing policy. |

Persistence loading retains the compatibility parser. This is preparation isolation
for a full behavior snapshot; arbitrary factory world side effects and exceptions
from controller/reset callbacks are not rolled back. Tree-only loading and explicit
definition application intentionally share existing memory with their factories.

The native creation fixture uses real BehaviorTrait save/load and native controllers
for both ordinary and packet NPCs. A factory captures scalar and nullable list values
during construction. Further probes reject a factory after a candidate memory write,
inject a memory read failure before any factory runs, compare saved states after
failure, and exercise partial loads, empty trees and despawn/respawn.

The unchanged implementation fails
`snapshot_factory_sees_restored_scalar_and_list_false` in
`artifacts/behavior-snapshot-memory-before.log`. NeoForge 21.1.233 and 21.1.248
each pass all 499 creation checks in `artifacts/behavior-snapshot-memory-native-233.log`
and `artifacts/behavior-snapshot-memory-native-248.log` with the fix.

BehaviorTrait is an existing Citizens feature, also present in the checked-in
original `main` sources. Interactions continues to own dialogue, choices, cutscenes
and player progress in its separate source set and NeoForge JAR.
