# Native dragon death runtime audit

Run from the repository root with PowerShell 7 and JDK 21:

~~~powershell
./tools/prepare-dragon-death-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/dragon-death-runtime-audit.gradle runServer --console=plain
~~~

Omit the NeoForge override to exercise default 21.1.233. The isolated fixture uses `artifacts/dragon-death-audit-server`, loopback port 25623, world `dragon-death-world` and marker `dragon-death-audit-fixture.txt`. Preparation verifies existing server identity and preserves the world. The fixture requires an empty default registry and creates a named memory registry, admitted synthetic players and a vanilla dragon control. Cleanup removes those NPCs/players, releases forced chunks and shuts down.

Success requires `[DRAGONDEATHAUDIT] COMPLETE`, no `[DRAGONDEATHAUDIT] FAILED`, and successful Gradle completion. The printed total includes checks repeated during real server ticks, so flight duration can change it. No test advances the Citizens scheduler or ticks dragons directly.

Coverage includes direct die, flying and hovering lethal damage, native dying-phase entry without die, cancellation followed by a later accepted death, direct kills, ordinary/virtual transport, complete 200-tick native death counters, exact post-animation respawn delays, Sentinel negative/zero/positive values, manual cancellation/replacement, death callback destruction/replacement, private virtual death sound and zero-health metadata. A living virtual control receives no native ticks. Each full death offers the native 500-point experience total through NeoForge events; the fixture sets the event reward to zero to avoid leaving orbs.

The longer flight uses a separate fight origin so it does not cross other controls or their respawned live replacements. The earlier crowded layout stalled one world dragon's native flight at one health; the retained diagnostic records that state. It is not evidence that a fixed timeout should replace native flight completion.

Related regressions are the ordinary/armor-stand death fixture, packet viewers, tracking admission, packet interactions and general runtime fixture, followed by clean test/build. Run Gradle/server invocations sequentially. Verify all release/source jars exclude audit code and keep Interactions separate. Physical-client animation/audio and full modpack acceptance remain separate validation.
