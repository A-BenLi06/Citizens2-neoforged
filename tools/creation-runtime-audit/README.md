# Native NPC creation runtime audit

The fixture currently has 328 checks. Template coverage includes sibling definitions,
overwrite index consistency, and applying the updated trait value through creation.
Behavior short-form coverage includes missing arguments/files, malformed YAML and a
valid filename containing spaces, with target preservation on file-input failures.
Behavior replacement checks reject an unknown node while preserving execution of the
old tree, blackboard identity and saved tree data.
Behavior lifecycle coverage includes repeated fresh saves, resaving a loaded tree,
and resuming execution after actual despawn/respawn.
It also removes BehaviorTrait from a live NPC and verifies execution stops.
Native integration also executes a parsed parallel tree that updates two blackboard
values and a string list, then restores that list from a BehaviorTrait snapshot.
Template lifecycle checks apply a failing action to a spawned NPC and verify the
failure is reported while the NPC's entity is restored.
Template event checks use the real event bus and command dispatcher to verify first-
application spawn commands, no duplicate listener after reapplication, and exclusion
of NPCs that have not received the template.
Invalid template-name coverage checks the localized command error and absence of
generation filesystem changes for an empty resource path.
A trait-parser probe destroys its target before returning an old trait and verifies
that neither attachment nor later parser invocations continue on the removed NPC.

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-creation-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/creation-runtime-audit.gradle runServer --console=plain
~~~

Omit the override to test default 21.1.233. The fixture uses loopback port 25625, `artifacts/creation-audit-server`, world `creation-world` and marker `creation-audit-fixture.txt`. Preparation validates existing server identity. The audit refuses a nonempty default registry and only tracks NPCs created after it begins. It cleans its NPCs, grants and block edits before shutdown.

Success requires `[CREATIONAUDIT] COMPLETE`, no `[CREATIONAUDIT] FAILED`, and successful Gradle completion. Expected creation/removal listener exceptions are deliberately exercised and logged; they must be followed by cleanup assertions and successful completion.

Coverage uses the real command dispatcher and native ticks: unspawned/baby/silent/packet/center options, partial collision shapes, item components and block-state projection, supported item carriers, default item providers, native falling/spawner controls, type/admin/limit checks, generic and command vetoes, creator events, callback removal/replacement, prepared templates, undo and nested history, zero/short/large lifetimes and replacement safety. The fixture generates its own template through the native template registry.

The retained diagnostics include a fixture metadata-access correction, the actual UUID-equality cleanup bug, a constructor callback bug, and the failed test's archived `RemovedDuringInit` record. Final source/test/archive evidence is in `artifacts/creation-validation-summary.json`. Run relocation, death, removal, hologram-transfer, item-hologram, command-location and general runtime regressions plus the Interactions parser tests, followed by clean test/build. Never include these fixture sources in release artifacts.
