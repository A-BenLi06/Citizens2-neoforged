# Packet hologram and mount runtime audit

The opt-in fixture uses `artifacts/packet-hologram-audit-server`, loopback port 25596 and its own `packet-hologram-world`. It requires an empty default Citizens registry. NPCs belong to a named in-memory registry; two synthetic players use actual PlayerList admission, protocol-configured embedded connections and native chunk-batch acknowledgements.

Run with JDK 21. Wait for every Gradle process to exit before starting the next. Prepare the existing regression fixtures according to their own READMEs first.

```powershell
./tools/prepare-packet-hologram-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-interaction-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-viewer-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/world-visibility-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/hologram-metadata-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/item-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/text-editor-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The fixture edits and reloads its actual Citizens config to exercise false/true/false packet-hologram settings, restoring false on cleanup. All eight built-in renderers and the name helper use normal creation and pairing paths. A packet-order replay maintains known entity IDs and mount relations, failing on any reference before spawn or after removal. Native attachment getters provide independent expected server positions; assertions never repair positions or manually alter tracker membership.

The final fixture passes 170 checks. The scenarios cover live parent/child visibility, packet range departure/reentry, personal text, conversion between world and virtual parents, child-before-vehicle creation, nested mounts, virtual player riders on native vehicles, vehicle/rider tracker replacement, dismount/remount, actual player respawn packets, dimension changes, NPC respawn, teardown and ordinary non-NPC controls. Name helpers honor configured renderer overrides; the fixture does not assume the reference's armorstand preference overrides those settings.

Reflection is read-only and limited to native chunk eligibility and final pairing-cache cleanup. Packet/profile emission does not establish physical-client visuals, interpolation, seat placement or hit testing. A missing completion marker, failure marker or three-minute Gradle timeout fails the run. Release/source jars must exclude every audit class and fixture.
