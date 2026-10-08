# Native NPC creation runtime audit

The fixture currently has 377 checks. Template coverage includes sibling definitions,
overwrite index consistency, and applying the updated trait value through creation.
Behavior short-form coverage includes missing arguments/files, malformed YAML and a
valid filename containing spaces, with target preservation on file-input failures.
Behavior replacement checks reject an unknown node while preserving execution of the
old tree, blackboard identity and saved tree data.
Behavior lifecycle coverage includes repeated fresh saves, resaving a loaded tree,
and resuming execution after actual despawn/respawn.
It also removes BehaviorTrait from a live NPC and verifies execution stops.
Ordinary and packet NPCs execute a repeated subtree containing a Molang wait over
real server ticks. The fixture verifies early non-completion, exactly three body
effects, a skipped zero-count body and progression to a signal-waiting successor.
The same live tree selects a grouped conditional's else branch, completes a timed
subtree and checks first-use/blocked-repeat cooldown behavior through an inverter.
Additional ordinary/packet trees place conditional groups directly under timeout,
invert, named-count repeat and loop nodes. Zero timeout prevents child effects;
repeat and loop each execute their waiting bodies three times on native ticks.
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

Success requires `[CREATIONAUDIT] COMPLETE`, no `[CREATIONAUDIT] FAILED`, and successful Gradle completion. Expected creation/removal listener exceptions are deliberately exercised and logged; they must be followed by cleanup assertions and successful completion.

Coverage uses the real command dispatcher and native ticks: unspawned/baby/silent/packet/center options, partial collision shapes, item components and block-state projection, supported item carriers, default item providers, native falling/spawner controls, type/admin/limit checks, generic and command vetoes, creator events, callback removal/replacement, prepared templates, undo and nested history, zero/short/large lifetimes and replacement safety. The fixture generates its own template through the native template registry.

The retained diagnostics include a fixture metadata-access correction, the actual UUID-equality cleanup bug, a constructor callback bug, and the failed test's archived `RemovedDuringInit` record. Final source/test/archive evidence is in `artifacts/creation-validation-summary.json`. Run relocation, death, removal, hologram-transfer, item-hologram, command-location and general runtime regressions plus the Interactions parser tests, followed by clean test/build. Never include these fixture sources in release artifacts.
