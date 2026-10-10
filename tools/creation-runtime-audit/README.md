# Native NPC creation runtime audit

The fixture currently has 521 checks. Template coverage includes sibling definitions,
overwrite index consistency, and applying the updated trait value through creation.
Behavior short-form coverage includes missing arguments/files, malformed YAML and a
valid filename containing spaces, with target preservation on file-input failures.
Behavior replacement checks reject an unknown node while preserving execution of the
old tree, blackboard identity and saved tree data.
Negative, malformed and overflowing wait literals are also rejected before replacing
the old execution, memory or saved definition.
Replacement also rejects multiple root nodes before choosing any of them, preserving
the running tree, blackboard and saved definition. Group multiple actions under a
single sequence or another explicit container.
Behavior lifecycle coverage includes repeated fresh saves, resaving a loaded tree,
and resuming execution after actual despawn/respawn.
It also removes BehaviorTrait from a live NPC and verifies execution stops.
Snapshot factories capture saved scalar/list values during construction on ordinary
and packet NPCs. Full restoration uses incoming memory rather than an empty or old
blackboard. Rejected factories and unreadable memory preserve the old tree, memory
identity and saved snapshot; candidate factory writes stay isolated. Memory-only
loads keep the current behavior context, tree-only loads and respawn use existing
memory, and explicit empty trees clear execution while restoring incoming memory.
The retained pre-fix log fails the first factory-capture assertion.
Root lifecycle probes run through each native NPC's behavior controller. Ordinary
and packet NPCs cover success, failure, removal, and exceptions during admission,
execution and cleanup. They verify one root cleanup per activation, no automatic
retry after an exception/removal, fresh NPC properties during admission, and recovery
after replacement and despawn/respawn. The pre-fix implementation fails the first
single-cleanup assertion; its log is retained as a negative control.
Ordinary and packet NPCs execute a repeated subtree containing a Molang wait over
real server ticks. The fixture verifies early non-completion, exactly three body
effects, a skipped zero-count body and progression to a signal-waiting successor.
The same live tree selects a grouped conditional's else branch, completes a timed
subtree and checks first-use/blocked-repeat cooldown behavior through an inverter.
The public retry decorator is also exercised through a fixture-only provider factory.
Ordinary and packet NPCs exhaust exactly three failed wait/action attempts, continue
to a sequence that succeeds on its third attempt, and stay stopped at a signal wait.
The retained pre-fix native log fails the finite-attempt assertion. This coverage
does not introduce a new built-in YAML behavior node.
Another fixture factory exercises the public timer API. Ordinary and packet NPCs
complete a one-tick action, reactivate the same two-tick timer across three waits,
skip zero-budget effects and continue after an over-budget wait expires. The old
implementation fails the one-tick action assertion. This also uses existing APIs
without registering a production YAML node.
Additional ordinary/packet trees place conditional groups directly under timeout,
invert, named-count repeat and loop nodes. Zero timeout prevents child effects;
The repeat body sets a dynamic timeout budget to 4, 3, 2 and 1 ticks across four
attempts at a two-tick wait. Exactly three effects complete; the fourth times out,
and its enclosing inverter allows progression. Loop executes its waiting body three times.
Later ticks verify that the completed containers remain stopped at a signal wait.
Separate ordinary/packet trees use scalar wait and wait_until configuration: false
keeps the tree blocked, while replacing it with true advances through the zero wait.
Sending the signal then resumes the real tree on later ticks. Native same-UUID NPCs
also verify directed behavior emission, exact-instance emission, explicit UUID matching
and independent listener cleanup.
Native integration also executes a parsed parallel tree that updates two blackboard
values and a string list, then restores that list from a BehaviorTrait snapshot.
Template lifecycle checks apply a failing action to a spawned NPC and verify the
failure is reported while the NPC's entity is restored.
Template event checks use the real event bus and command dispatcher to verify first-
application spawn commands, no duplicate listener after reapplication, and exclusion
of NPCs that have not received the template.
Reloaded template definitions replace spawn commands only on the reapplied NPC;
other NPC subscriptions survive, and removing the event block removes its commands.
Invalid template-name coverage checks the localized command error and absence of
generation filesystem changes for an empty resource path.
A trait-parser probe destroys its target before returning an old trait and verifies
that neither attachment nor later parser invocations continue on the removed NPC.
The default trait parser also rejects an invalid persisted Age field and accepts a
repaired value without altering the target on the rejected attempt.
Generated-template disk round trips also preserve native behavior blackboard lists,
including null slots and explicitly empty lists.
Template command dispatch also verifies explicit selection by numeric ID, positional
name, named flag and UUID through the native command dispatcher.
Implicit template/scheduled targets are checked across named, anonymous and temporary
registries with colliding IDs and UUIDs, including extra command whitespace, explicit
default-registry targeting and replacement of a target before the scheduled tick.

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-creation-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/creation-runtime-audit.gradle runServer --console=plain
~~~

Omit the override to test default 21.1.233. The fixture uses loopback port 25625, `artifacts/creation-audit-server`, world `creation-world` and marker `creation-audit-fixture.txt`. Preparation validates existing server identity. The audit refuses a nonempty default registry and only tracks NPCs created after it begins. It cleans its NPCs, grants and block edits before shutdown.

Success requires `[CREATIONAUDIT] COMPLETE`, no `[CREATIONAUDIT] FAILED`, and successful Gradle completion. Expected creation/removal listener and behavior-root exceptions are deliberately exercised and logged; they must be followed by cleanup assertions and successful completion.

Coverage uses the real command dispatcher and native ticks: unspawned/baby/silent/packet/center options, partial collision shapes, item components and block-state projection, supported item carriers, default item providers, native falling/spawner controls, type/admin/limit checks, generic and command vetoes, creator events, callback removal/replacement, prepared templates, undo and nested history, zero/short/large lifetimes and replacement safety. The fixture generates its own template through the native template registry.

The retained diagnostics include a fixture metadata-access correction, the actual UUID-equality cleanup bug, a constructor callback bug, and the failed test's archived `RemovedDuringInit` record. Final source/test/archive evidence is in `artifacts/creation-validation-summary.json`. Run relocation, death, removal, hologram-transfer, item-hologram, command-location and general runtime regressions plus the Interactions parser tests, followed by clean test/build. Never include these fixture sources in release artifacts.
