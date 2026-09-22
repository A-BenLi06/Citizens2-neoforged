# Packet NPC viewer lifecycle audit

This opt-in fixture runs in `artifacts/packet-viewer-audit-server` on loopback port 25592, with its own `packet-viewer-world`. It requires the preparation marker and an empty default registry. Test NPCs use a named in-memory registry. Three synthetic players use real PlayerList admission and native packet serialization over embedded connections.

Use JDK 21 and run Gradle sequentially:

```powershell
./tools/prepare-packet-viewer-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-viewer-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/item-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The 43 checks cover virtual world absence, native pairing/equipment/metadata packets, stable membership, range exit/reentry and live range changes, spectator/invisible viewers, default and reloaded PlayerFilter rules, parent filters and redirect cycles, actual respawn/dimension travel/relogin/disconnect, packet/real controller transitions, API trait attachment/replacement and deferred-removal cleanup. Packets are captured after the native bundle generation path and sent through the configured protocol encoder. No physical client is connected.

The launch fails on a failure marker or absent completion and has a three-minute timeout. Release/source jars must exclude this fixture. Packet-hologram configuration, per-viewer text, mounted packet hierarchies, packet player skin presentation, ordinary world-entity visibility filtering and client/modpack acceptance are separate work; these tests do not establish those behaviors.
