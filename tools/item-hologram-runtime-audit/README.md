# Item hologram runtime audit

The opt-in audit uses `artifacts/item-hologram-audit-server`, loopback port 25591 and its own `item-hologram-world`. It requires the preparation marker and an empty default NPC registry. Parent NPCs belong to a named in-memory registry; helpers use the ordinary Citizens temporary registry. No production data belongs in this fixture.

Use JDK 21 and run Gradle sequentially:

```powershell
./tools/prepare-item-hologram-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/item-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/text-editor-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The 101 checks run across real server ticks. They cover material/color/component parsing, invisible point anchors, item passengers, native pickup/lifetime flags, movement, default margins, parent click redirects, temporary registry membership, changing item/text modes, unavailable markup file retention, live view-range updates, parent despawn/respawn, NPC copies, temporary expiry, externally removed helper recovery, item-display mounting/type persistence and full cleanup. Filter child counts are inspected to verify that repeated rebuilds remove obsolete IDs.

Failure markers or missing completion fail Gradle; the launch has a three-minute timeout. Release and source jars must exclude this audit. The fixture inspects native entity state and click-redirect contracts, not physical-client rendering or actual mouse input. Per-viewer packet holograms, provider item models, cross-dimension/client presentation and the combined modpack still need their own acceptance.
