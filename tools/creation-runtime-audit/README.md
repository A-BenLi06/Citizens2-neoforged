# Native NPC creation runtime audit

The fixture currently has 306 checks. Template coverage includes sibling definitions,
overwrite index consistency, and applying the updated trait value through creation.

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-creation-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/creation-runtime-audit.gradle runServer --console=plain
~~~

Omit the override to test default 21.1.233. The fixture uses loopback port 25625, `artifacts/creation-audit-server`, world `creation-world` and marker `creation-audit-fixture.txt`. Preparation validates existing server identity. The audit refuses a nonempty default registry and only tracks NPCs created after it begins. It cleans its NPCs, grants and block edits before shutdown.

Success requires `[CREATIONAUDIT] COMPLETE`, no `[CREATIONAUDIT] FAILED`, and successful Gradle completion. Expected creation/removal listener exceptions are deliberately exercised and logged; they must be followed by cleanup assertions and successful completion.

Coverage uses the real command dispatcher and native ticks: unspawned/baby/silent/packet/center options, partial collision shapes, item components and block-state projection, supported item carriers, default item providers, native falling/spawner controls, type/admin/limit checks, generic and command vetoes, creator events, callback removal/replacement, prepared templates, undo and nested history, zero/short/large lifetimes and replacement safety. The fixture generates its own template through the native template registry.

The retained diagnostics include a fixture metadata-access correction, the actual UUID-equality cleanup bug, a constructor callback bug, and the failed test's archived `RemovedDuringInit` record. Final source/test/archive evidence is in `artifacts/creation-validation-summary.json`. Run relocation, death, removal, hologram-transfer, item-hologram, command-location and general runtime regressions plus the Interactions parser tests, followed by clean test/build. Never include these fixture sources in release artifacts.
