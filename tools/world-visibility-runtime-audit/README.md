# Native world visibility audit

This opt-in fixture runs in `artifacts/world-visibility-audit-server` on loopback port 25593 with its own `world-visibility-world`. Its default NPC registry must be empty. NPCs use an in-memory registry and synthetic viewers enter through the actual PlayerList over protocol-configured embedded connections.

Use JDK 21, and wait for each Gradle command to finish before starting another:

```powershell
./tools/prepare-world-visibility-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/world-visibility-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-viewer-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/item-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The audit captures native chunk/spawn/profile/removal/metadata/equipment packets and reads actual tracker membership. It acknowledges received chunk batches using the native server handler. Reflection only reads the package-private `isChunkTracked` predicate; the fixture never inserts tracker membership, calls direct pairing, or changes the native visibility answer. The stationary non-NPC control is inserted after chunk delivery, since vanilla otherwise waits for section movement before its next eligibility calculation.

The 47 checks cover initial UUID denial, profile-before-spawn ordering, text/item/anchor helpers, live deny/allow/clear/trait removal, apply range, external predicate changes without `recalculate`, redirect cycles, native range/chunk-watch behavior, dimension travel and watched NPCs outside simulation distance. The latter requires client/server view distance 6 and simulation distance 3. Runtime checks fail on a missing completion marker or failure marker; the Gradle run also has a three-minute timeout.

These checks establish server tracking and protocol behavior. They do not establish physical-client rendering, combined modpack compatibility, all separate scoreboard packets, event cancellation semantics, or packet hologram presentation. Audit classes and fixture data must remain absent from release/source jars.
