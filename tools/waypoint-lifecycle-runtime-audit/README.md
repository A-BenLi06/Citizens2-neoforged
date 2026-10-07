# Waypoint lifecycle audit

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-waypoint-lifecycle-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/waypoint-lifecycle-runtime-audit.gradle runServer --console=plain
~~~

Omit the version override for default 21.1.233. The fixture uses `artifacts/waypoint-lifecycle-audit-server`, loopback port 25629, world `waypoint-lifecycle-world` and marker `waypoint-lifecycle-audit-fixture.txt`. It requires an empty default registry and releases its NPCs, player, grant and forced chunk before shutdown.

Success requires `[WAYPOINTLIFECYCLEAUDIT] COMPLETE 232 checks`, no failure marker and successful Gradle completion. Commands use real dispatch and an admitted synthetic player. Controlled native navigation strategies let tests deterministically complete/cancel routes and replace them from navigation-begin callbacks; cached paths also pass through the actual supplied-path backend.

Wander behavior cases verify goal startup/run, owned cancellation, preservation of a
foreign request, reset during NavigationBegin and positional/named pathfind=false.
Direct-movement admission retries through normal server ticks when random destination
selection temporarily finds no valid standing position.

Walkto behavior checks assert speed/range/margin values at native strategy construction,
unchanged default parameters and preservation of an active request after invalid input,
for ordinary and packet NPCs.
Walkto lifecycle cases distinguish cancellation/replacement from actual arrival, cancel
only owned requests on reset, preserve a foreign request and reject publication after
reset during strategy preparation.

Wander-command checks reject invalid input before trait attachment/provider replacement,
preserve existing and unavailable routes, and verify valid boolean/range/region settings
plus default-seconds and explicit-ticks duration forms.

Strict loading checks retain complete provider data when a trigger type is absent or
a known trigger has malformed fields. The route stays unavailable instead of losing
its failed children, and a corrected record restores its trigger on retry.

Coverage includes invalid-command atomicity, unspawned locations, ordered insertion and removal, provider factory/switch/load/despawn/removal behavior, callback reentry, unavailable/disabled provider saves, nested list isolation, guided point replacement/reload, linear/guided/wander delays for world/virtual NPCs, existing/later pauses, route edits and recursive arrivals, trigger snapshots, cancellation versus arrival, cached-route replay, early configuration callbacks and preservation of replacement navigation.

The negative run proves the original unknown-provider route loss. The four-check repair and expanded 113-check run precede the final successful-arrival/cache checks. A retained cache diagnostic used a route without ground support; the corrected fixture uses the flat world's ground. Related validation runs follow, movement/list, creation, removal, mount-transfer and general runtime fixtures, full Interactions tests and ordinary clean test/build. Run all Gradle/server invocations sequentially and verify test classes stay out of release/source jars. Evidence: `artifacts/waypoint-lifecycle-validation-summary.json`.
