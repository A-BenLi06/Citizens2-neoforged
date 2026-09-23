# NPC seen-event admission audit

The opt-in fixture uses `artifacts/seen-event-audit-server`, loopback port 25597 and its own `seen-event-world`. It requires an empty default Citizens registry. Test NPCs use a named in-memory registry; synthetic players use real PlayerList admission, native protocol encoding and chunk-batch acknowledgements.

Run from the repository root with JDK 21. Every Gradle process must finish before the next starts.

```powershell
./tools/prepare-seen-event-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/seen-event-runtime-audit.gradle runServer --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Seen-event audit failed' }
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Test/build failed' }
```

The fixture passes 108 checks using actual world and PacketNPC tracking. World/virtual cows, player NPCs and text displays exercise cancellation, retry, membership, mirrored and cosmetic equipment, live cosmetic changes, range reentry, filters, renderer callbacks, synchronous reentrant tracking and listener-triggered destruction. Packet capture verifies admission precedes spawn and supplemental equipment follows it. Native StartTracking is counted independently. Assertions never synthesize Citizens seen events or manually pair entities.

The reentrant listener deliberately refreshes normal native/packet tracking; the assertion distinguishes legitimate nested admission for another viewer from recursive admission for the same entity/viewer. Reflection only reads native chunk eligibility and final admission-guard state. A missing completion marker, failure marker or three-minute timeout fails the run. Release/source artifacts must exclude this fixture.

The recorded regression also uses the existing packet-hologram, interaction, packet-viewer, world-visibility, hologram-metadata, item-hologram, text-editor, removal and general runtime fixtures. Prepare each according to its own README and run sequentially before the final normal test/build. None of these synthetic clients proves physical rendering, target selection or combined modpack acceptance.
