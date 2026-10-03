# Native default-store replacement audit

Run from the repository root with PowerShell 7 and JDK 21. Every Gradle/server invocation must finish before starting the next:

~~~powershell
./tools/prepare-store-switch-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' '-PstoreSwitchPhase=runtime' -I ../tools/store-switch-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' '-PstoreSwitchPhase=early' -I ../tools/store-switch-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' '-PstoreSwitchPhase=late' -I ../tools/store-switch-runtime-audit.gradle runServer --console=plain
~~~

Repeat the three runs without the version override for default NeoForge 21.1.233. The fixture uses `artifacts/store-switch-audit-server`, loopback port 25632 and world `store-switch-world`. The ordinary default store must be empty. All custom store records are synthetic. `early` supplies the store before Citizens' starting hook; `late` uses the public API after that hook but before server-started loading. Both verify that the override loads once and is not saved while still unloaded.

Observable custom stores verify loading, ID allocation, deletion, immediate saving, source reload, rejected recursive/off-thread operations and failure recovery. Normal and packet NPCs use actual native entities and respawn ticks. Cases include canceled creation, a partially spawned failed candidate, shared-UUID queues in different registries, stale tasks, vetoed despawning, failing trait cleanup, forward/missing click redirects and a switch initiated by an NPC's own update callback. The prior binding is restored, test registries are removed and forced chunks are released before shutdown.

Success requires `[STORESWITCHAUDIT] COMPLETE ... phase=...`, no fixture failure/update/event errors, and successful Gradle exit. The initial registry-binding and redirect failures are retained as negative evidence, not acceptance runs. Related validation covers the existing datastore provider cycle, creation, removal, selection, packet interactions, general native probes, ordinary tests and Interactions tests. Finish with a normal clean test/build and verify release/source archives exclude fixtures and keep Interactions separate.

Final native results are 78/81/81 checks for runtime/early/late phases on each NeoForge version, 480 checks total. All related regressions, 262 ordinary tests, 100 Interactions tests, clean build and archive checks pass. Implementation and results: [default-store replacement](../../docs/npc-store-replacement-2026-10-03.md). Consolidated evidence: `artifacts/store-switch-validation-summary.json`.
