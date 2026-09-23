# Per-viewer hologram metadata audit

The opt-in fixture uses `artifacts/hologram-metadata-audit-server`, loopback port 25594 and its own `hologram-metadata-world`. The default registry must be empty. Two synthetic players enter the native PlayerList with protocol-configured embedded connections and acknowledge actual chunk batches. Test NPCs use a named in-memory registry.

Run with JDK 21 and wait for each Gradle process to exit before starting the next:

```powershell
./tools/prepare-hologram-metadata-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/hologram-metadata-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/world-visibility-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-viewer-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/item-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/text-editor-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The 179 checks retain personalized native pairing/update packets, parent NPC context, world/packet transport, immutable input packets and shared entity templates, dynamic registered values without template edits, preserved unrelated metadata, hidden-viewer exclusion, fresh pairing after visibility returns, blank per-viewer names, unchanged-value suppression, a non-NPC control and cache cleanup through despawn/respawn/destruction. A same-tick edit after a viewer leaves packet range must send no supplemental update, before the normal packet reconciliation runs.

The sneaking extension covers opposite per-viewer flags on world/virtual armor stands and text displays, both boolean transitions, null text overrides, preserved incoming/shared flag bytes and pose, and current state after visibility/range reentry and respawn. Stationary parent changes must update world and packet name helpers without invoking position rendering. Live packet conversion replaces the entity; the test reacquires and verifies the active virtual helper before testing its metadata. A focused packet-shape check uses a captured native spawn in a metadata-free bundle to verify that the override follows the spawn and leaves the input bundle intact; this shape check is separate from the actual pairing coverage.

A test renderer attaches the existing PacketNPC trait before spawning its helper. It does not enable a global packet-hologram setting or bypass pairing. Reflection is read-only: it observes native chunk eligibility and the viewer cache for cleanup assertions. Packet fields use native typed data accessors, without numeric metadata indices.

This fixture does not establish physical-client rendering, packet mounts, virtual entity interaction routing or combined modpack acceptance. A missing completion marker, failure marker or three-minute Gradle timeout fails the run. Release/source jars must exclude all audit classes and fixtures.
