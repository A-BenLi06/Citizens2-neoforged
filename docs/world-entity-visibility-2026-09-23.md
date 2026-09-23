# Native world-entity visibility

Updated: 2026-09-23 10:29:25 +08:00 (UTC+08:00).

Ordinary NPCs and their hologram helpers now apply Citizens visibility through the native world tracker. A hidden player NPC does not send its profile or entity spawn. A live rule change removes the viewer from native tracking; allowing that viewer again sends fresh pairing data while the NPC remains in the server world.

## Native ownership

The reference `EventListen.onNPCSeenByPlayer` applies PlayerFilter to an NPC and its click-redirect parent. The port previously emitted its event from NeoForge StartTracking, after the spawn bundle had already been sent, and the ordinary tracker did not consult the filter. The existing packet NPC path had a separate visibility consumer.

`TrackedEntityMixin` wraps the native `Entity.broadcastToPlayer` call in `ChunkMap.TrackedEntity.updatePlayer`. The original result must succeed, then Citizens checks the NPC and all click-redirect ancestors. Hidden or unspawned ancestors and redirect cycles reject visibility. Vanilla retains control of horizontal range, chunk watch, viewer membership and spawn/removal packets. Packet NPCs reuse this same Citizens policy while retaining their separate transport and eligibility checks.

`ChunkMapMixin` refreshes NPC viewer decisions during the existing per-entity tracking loop, before the native section-change/ticking decision and broadcasts. It passes the entity world's players, matching vanilla's world-scoped tracking assumptions. This also covers watched, stationary NPCs outside simulation distance: those entities skip `ServerEntity.sendChanges`, so refreshing only there would leave live filters stale. Non-NPC entities retain their existing eligibility behavior.

The native `ServerEntity` pairing path still supplies player profiles before entity spawn, and the existing StopTracking handler removes profiles. This batch does not change those mechanisms or send synthetic substitute entity packets.

## Validation

See the [isolated fixture instructions](../tools/world-visibility-runtime-audit/README.md). The fixture uses actual PlayerList admission, native chunk delivery and batch acknowledgements, native tracker membership and protocol-encoded packet capture. Synthetic clients explicitly request six chunks of view distance, while server simulation distance is three. The default native `ClientInformation.createDefault()` requests only two chunks and cannot establish the distant acceptance case.

The dedicated audit passes 47 checks, including initial denial, profile ordering, helper visibility, live rules, range/chunk behavior, dimension changes and the non-ticking case. Sequential NeoForge 21.1.248 gates also pass 43 packet-viewer checks, 101 item-hologram checks, 62 removal checks, 107 NPC assertions, 223 general probes and 217 ordinary tests with zero failures/errors/skips. Normal build succeeds. Each process was terminal before the next Gradle launch.

Release/source jars contain the visibility classes and exclude audit classes/fixtures. The release registers the required tracking mixin. Citizens SHA-256 is `cbb1a5732d333efedd119698dfe7cba93e0f5e0c325fe2dc6c1dd41b5c1b0dea`; Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Detailed counts, source/reference/log/report/jar hashes and the unchanged original-save check are in `artifacts/world-visibility-validation-summary.json`.

## Remaining boundaries

This establishes entity/profile tracking, not secrecy of every separate packet system. Scoreboard/team packets have their own behavior. Cancellation semantics of `NPCSeenByPlayerEvent` remain separate work because that event still originates after native pairing. Physical-client rendering, target-modpack compatibility and proxy acceptance are unverified. Packet hologram configuration, per-viewer text and mounted packet hierarchies also remain open.

The retained provider scope remains [Citizens with the native ecosystem](neoforge-ecosystem-replacements-2026-09-22.md). No installed provider is replaced and no production data is deployed by this batch.
