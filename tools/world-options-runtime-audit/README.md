# Anchor, chunk-loading and fluid-push audit

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-world-options-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/world-options-runtime-audit.gradle runServer --console=plain
~~~

Omit the version override to test default NeoForge 21.1.233. The fixture uses `artifacts/world-options-audit-server`, loopback port 25627, world `world-options-world`, and marker `world-options-audit-fixture.txt`. It requires an empty default registry. It restores changed blocks and releases its NPCs, named registry, players, permission grants and forced chunks before shutdown.

Success requires `[WORLDOPTIONSAUDIT] COMPLETE 213 checks`, no failure marker and successful Gradle completion. Commands execute through the real dispatcher with an admitted synthetic player. Ticket assertions inspect the native forced-chunk tracker; fluid assertions invoke native flow calculation over a water/lava gradient.

Coverage includes cursor/source caches and current rotation, explicit locations, anchor validation/case/persistence/copies, unavailable worlds, canceled teleport and action precedence; temporary metadata save/clone/reload; immediate chunk tickets, chunk/dimension transfer, replaced traits, shared UUIDs across registries and independent vanilla force-loading; protected/default/explicit fluid policy for world and virtual cow/player NPCs, retained death visuals, native turtle vetoes, fluid contact and unchanged prior motion.

Diagnostic logs distinguish the initial import typo, stale-head targeting and trait event-registration failures from fixture assumptions about water/lava replacement and boat fluid behavior. The final fixture uses native turtles as the no-push control. Physical-client behavior and arbitrary modded fluid/entity implementations remain acceptance work.

Related regressions cover relocation, command locations, mounted transfer, scoreboard collision, death and the general runtime fixture. Run the full Interactions tests and ordinary clean test/build, then inspect release/source archives for audit-code exclusion and separate Citizens/Interactions contents. Evidence is consolidated in `artifacts/world-options-validation-summary.json`.
