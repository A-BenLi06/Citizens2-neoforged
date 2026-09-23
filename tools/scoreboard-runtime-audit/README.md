# Isolated scoreboard lifecycle audit

Run from the repository root to prepare the loopback-only fixture on port 25601:

```powershell
./tools/prepare-scoreboard-audit.ps1
```

From `neoforge`, run each Gradle command after the previous one exits:

```powershell
$env:JAVA_HOME = 'C:/Users/ben_l/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/scoreboard-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/seen-event-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/hologram-metadata-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/interaction-label-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/item-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/world-visibility-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

Other dedicated fixtures must already be prepared using their own instructions. Never point these audits at a production world. The final normal build removes the opt-in audit source directories from release/source jars; verify those jars contain no audit classes or fixtures.

The dedicated fixture passes 312 checks. It admits three synthetic players through actual `PlayerList` and protocol encoders, acknowledges native chunks, and lets real world/virtual tracking pair NPCs. It replays outgoing team packets into native scoreboard objects with the same ordering as `ClientPacketListener.handleSetPlayerTeamPacket`. Duplicate ADDs and updates for unknown teams fail the run. Respawn enters the normal `PERFORM_RESPAWN` packet handler; dimension changes use native teleportation. Each connection owns one replay scoreboard across local player/level replacement, matching the client listener's lifetime.

Scenarios cover initial property/profile/entity order, global team distribution, filters/range/reentry, changed and unchanged properties, one-recipient preparation, membership replacement, null/reset/style colors, persisted styles and commands, NPC rename/respawn, disable/enable, same-UUID reconnect, player respawn, dimension travel, trait replacement, failed spawn/retry and destruction. Read-only reflection inspects private maps after cleanup. An unrelated server team must remain intact.

The Gradle hook requires the fixture marker, a completion marker and no failure marker, with a three-minute timeout. Native packets and state are verified; physical-client rendering and full modpack/proxy compatibility are not implied.
