# HPA graph and command audit

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-hpa-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/hpa-runtime-audit.gradle runServer --console=plain
~~~

Omit the version override for default NeoForge 21.1.233. The fixture uses `artifacts/hpa-audit-server`, loopback port 25630, world `hpa-world` and marker `hpa-audit-fixture.txt`. It requires an empty default NPC registry. Its synthetic players, NPCs, temporary permission changes, block edits, six forced chunks and background jobs are released before shutdown. No production save or modpack is used.

Success requires `[HPAAUDIT] COMPLETE 109 checks`, no failure marker and successful Gradle completion. A real command dispatcher and admitted player connections exercise both aliases, operator and permission checks, console rejection, invalid arguments/configuration, different worlds, unspawned/ordinary/virtual NPCs, unloaded endpoints, high and negative Y, cross-region paths and unreachable endpoints. Active navigation and waypoint providers must remain unchanged.

The snapshot checks copy native palettes, edit the live blocks afterwards, and calculate paths on a background thread. They cover actual dimension bounds, fluids, collision coordinates, known air versus uncaptured blocks and rejection of off-thread capture. An unloaded endpoint is represented by an unspawned NPC's stored location: moving a live player can itself load chunks, independently of HPA.

A latch holds the diagnostic executor to test queue limits, invalid-request preservation, request replacement, NPC/registry removal, loss of operator status or permission, changed world, replacement player sessions, deadlines and shutdown. These tests inspect task cancellation and queue ownership as well as player feedback. Stale requests must cancel within five ticks without timeout feedback; the permission test verifies that the native permission check actually returns false. One deliberately throwing permission callback logs `hpa-audit-injected-permission-failure`; the request must fail without stopping the server. Message assertions use the same translation and highlight formatting as Citizens.

Run the deterministic algorithm tests separately:

~~~powershell
./neoforge/gradlew.bat -p neoforge test --tests 'net.citizensnpcs.api.hpastar.HPAGraphTest' --console=plain
~~~

They cover all four abstraction levels, negative/high/separated-by-256 Y, dimensions excluding Y=0, adjacent-region insertion order, half-open cluster boundaries, repeated portal queries, blocked corners and low ceilings, stepped vertical crossings, unreachable paths, failed refinement, dirty rebuilds, cancellation, settled-goal A* and confinement of parent edges to their footprint.

Both NeoForge versions pass the 109-check fixture. The 16 algorithm tests, all 249 ordinary tests and all 100 Interactions tests pass, along with waypoint, movement/list, follow, removal and general regressions. Run every Gradle/server invocation sequentially and wait for its process to terminate. Finish with an ordinary clean test/build and verify that release/source archives exclude audit classes and that Interactions remains a separate JAR. Consolidated evidence is recorded in `artifacts/hpa-validation-summary.json`. The earlier 99-check run is retained as a diagnostic, not final acceptance, because permission revocation there passed by timeout.
