# Isolated skin refresh audit

Prepare the loopback-only fixture on port 25602 from the repository root:

```powershell
./tools/prepare-skin-refresh-audit.ps1
```

From `neoforge`, run commands sequentially, waiting for each process to exit:

```powershell
$env:JAVA_HOME = 'C:/Users/ben_l/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/skin-refresh-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/scoreboard-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/seen-event-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/hologram-metadata-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/movement-list-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/world-visibility-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

Other fixtures require their existing preparation scripts. Do not use production worlds. The normal final build excludes these opt-in fixture sources; verify release/source jars contain no audit code.

The dedicated fixture passes 367 checks using synthetic players, real PlayerList admission, protocol encoders, native chunk acknowledgements and actual tracker membership. It captures profile/entity/equipment/attribute/metadata/mount/list packets. Profile packets are decoded into detached GameProfiles; the client model separately retains the profile map and an entity's cached PlayerInfo, matching inspected 1.21.1 source. A negative control reproduces the old profile-only refresh and demonstrates the stale entity cache before the real refresh fixes it.

Scenarios cover world/virtual skins and mirrors, source/packet immutability, custom equipment functions, first pairing and full refresh ordering, native server identity, both sides of mount chains, a real mounted viewer, current listed policy, range/filter/reentry, same-UUID reconnect, native player respawn requests, cancelled admission, recursive refresh, destruction and tracker replacement during callbacks, and cleanup of queued delayed work. Persistent Citizens UUIDs are not assumed to equal Minecraft profile UUIDs.

The 48 command checks toggle `/npc mirror --name true`, `false`, then `true` on world and virtual NPCs. The first profile must already use the requested name and retain the NPC Minecraft UUID. Commands advance across ordinary trait ticks to accommodate virtual initial pairing. Old client entities, cached profile identity, distant exclusion and final cleanup are also checked.

The fixture requires its marker, a completion marker, no failure marker and completion within the three-minute Gradle timeout. It uses synthetic texture payloads to verify protocol identity and cache lifetimes; it does not claim real texture download/signature validation or physical-client drawing/modpack/proxy acceptance.
