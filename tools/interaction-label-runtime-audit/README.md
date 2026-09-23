# Native Interaction label audit

The opt-in fixture uses `artifacts/interaction-label-audit-server`, loopback port 25600 and its own `interaction-label-world`. The default NPC registry must be empty. Two synthetic players enter the native PlayerList with protocol-configured embedded connections and acknowledge actual chunk batches; fixture NPCs use a named in-memory registry.

Run with JDK 21 and wait for each Gradle process to exit before starting the next:

```powershell
./tools/prepare-interaction-label-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/interaction-label-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/hologram-metadata-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/packet-hologram-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/seen-event-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

Real outgoing spawn/metadata/remove packets maintain unregistered native Interaction replicas. Each metadata packet is applied through `SynchedEntityData.assignValues`, exactly as `ClientPacketListener.handleSetEntityData` does. The audit never calls `refreshDimensions` or repairs a replica's pose/attachments itself. It inspects the resulting native `NAME_TAG` attachment and compares the resulting label height with the independently authored offset and parent's actual bounding-box top. It also inspects server helper attachments and every captured shape packet's field order/height.

The fixture passes 393 checks, including per-packet shape/order checks. Coverage includes first pairing, native mounts, separate world/packet helpers, per-viewer text and sneaking with unchanged shared pose, movement, scale, negative margins, line spacing, filters, same-tick packet-range exclusion, reentry, camel seats, a virtual parent, transport replacement, unchanged-value suppression, respawn and cleanup. Real passengers directly beneath a virtual root and beneath an intermediate virtual helper tick exactly once per native level tick, while virtual nodes remain unsimulated; freezing the root also freezes native passenger traversal. A failed renderer initializer must discard its unpaired entity/mount and allow a fresh retry. A focused packet test preserves an incoming non-default real pose/shape in the overlay, verifies immutable input, and leaves a non-NPC Interaction packet untouched.

The reported assertion total includes checks for each captured metadata packet and can vary with native packet timing; every scenario must complete and every captured shape packet must pass. The final recorded run has 393 assertions.

Replicas use the loaded server level without world insertion. Interaction's native `noPhysics` flag excludes the server-only collision adjustment in `refreshDimensions`; the metadata and attachment callbacks themselves are shared native code. This verifies metadata-driven geometry, not physical-client drawing, interpolation, hit testing or complete modpack compatibility. Native horizontal seat behavior is retained. A missing completion marker, failure marker or three-minute Gradle timeout fails the run. Release/source jars must exclude audit classes and fixtures.
