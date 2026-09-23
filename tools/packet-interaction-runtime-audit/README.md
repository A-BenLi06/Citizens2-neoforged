# Native packet NPC interaction audit

This opt-in fixture uses `artifacts/packet-interaction-audit-server`, loopback port 25595 and its own `packet-interaction-world`. The default Citizens registry must be empty. Two synthetic players enter the actual PlayerList using protocol-configured embedded connections and native chunk-batch acknowledgements. NPCs belong to a named in-memory registry.

Run with JDK 21. Wait for each Gradle process to exit before starting another:

```powershell
./tools/prepare-packet-interaction-audit.ps1
Set-Location neoforge
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

Every interaction passes through the native `ServerboundInteractPacket.STREAM_CODEC` and `ServerGamePacketListenerImpl.handleInteract`. Primary assertions never call Citizens events directly. Commands execute through the real scheduler and dispatcher; command assertions wait until the next phase. NPC command cooldowns are explicitly disabled for this fixture so they cannot hide duplicate dispatch.

The fixture passes 78 checks. Coverage includes paired virtual targets absent from world storage; unpaired, stale, hidden, distant, disconnected and wrong-dimension rejection; helper/parent filters and cycles; ordinary NPC and non-NPC controls; interleaved viewers; reentrant events; command and event cancellation; delayed cancellation without commands; unhandled native feeding; offhand hit vectors and secondary actions; attack routing; native reach attributes, world borders, disabled item features and invalid item-entity attacks; trait replacement, despawn/respawn, removal-to-world transitions and index cleanup. A fixture-only registered item requires the disabled trade-rebalance feature to exercise vanilla's item gate.

Player respawn uses the native client-command handler, which replaces the player and rebinds the listener. Calling PlayerList.respawn alone does not perform that listener reassignment. Trait replacement can pair immediately because the existing Trait.isRunImplemented probe calls run during attachment; the audit observes that actual behavior. A same-tick replacement after respawn exercises player object identity even though vanilla retains the old numeric entity ID. Removing that replacement must release its own tracker, native remove packet and lookup entry.

Reflection only observes native chunk eligibility and the packet target index size. No manual pairing or tracker membership changes are used. The fixture does not establish physical-client target selection, renderer clickability, cross-tick mouse-input timing, mounted packet hierarchies or combined modpack acceptance. Missing completion markers, failure markers and the three-minute Gradle timeout fail the run. Release/source jars must exclude the entire audit package and fixture item.
