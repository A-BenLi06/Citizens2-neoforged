# Cancellable NPC tracking admission

This Citizens core follow-up follows the [retained NeoForge ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md). It repairs an existing extension event and its built-in consumers; no provider is selected or replaced.

## Event contract

`NPCSeenByPlayerEvent` now fires before a new viewer enters tracking and before native profile, spawn, metadata or equipment packets. Canceling prevents that attempt from recording the viewer or sending pairing data. Canceled attempts are retried by normal tracking updates; no rejection cache requires an addon to invalidate it. Already tracked viewers do not receive repeated admission events. Leaving and reentering tracking requires fresh admission. To hide an existing viewer, use the NPC's visibility rules.

The checked-in `v1_21_R7` CitizensEntityTracker reference likewise uses the event for unpaired viewers, not live revocation. The NeoForge port checks native world range/chunk eligibility and Citizens visibility before dispatch rather than sending events for unrelated, ineligible candidates. PacketNPC retains its existing authoritative player identity, dimension and tracking-box eligibility.

Native `ChunkMap.TrackedEntity.updatePlayer` now wraps its viewer-set insertion. A canceled attempt leaves native membership untouched, so vanilla still owns successful pairing, range/chunk removal and tracking packets. `EntityPacketTracker.link` applies the same admission contract before recording a virtual viewer. Ordinary non-NPC admission retains its native behavior.

Identity-based guards prevent recursively dispatching admission for the same entity/viewer while allowing independent viewers to proceed. Guards exist only during synchronous callbacks and are released in `finally`. Visibility, ownership, transport, player identity and entity liveness are rechecked after dispatch. PacketNPC also stops an in-progress viewer loop when a callback despawns or replaces its entity/trait. A removed entity cannot become a non-NPC pairing candidate just because destruction removed its NPC association; pending links no longer revive discarded entities.

## Successful pairing callbacks

NeoForge's noncancellable `PlayerEvent.StartTracking` remains the notification after actual native pairing. The old duplicate `NPCSeenByPlayerEvent` emission there is removed. Hologram renderer callbacks retain their successful-pairing timing.

Mirror and cosmetic equipment use a shared trait event extractor for the native StartTracking target. Each successful pairing clears the viewer's mirror snapshot and sends their personalized equipment after spawn. The cosmetic overlay also follows native pairing; its previous seen-event callback became too early once admission moved before spawn. Live cosmetic updates now include virtual viewers. Both forms require actual current world or packet tracker membership and live visibility. Previously the mirror proximity loop could send equipment to a denied or unpaired viewer. This change does not claim privacy of unrelated scoreboard packets.

## Validation

The [isolated audit](../tools/seen-event-runtime-audit/README.md) passes 108 checks on NeoForge 21.1.248. Two synthetic players enter the actual PlayerList with protocol-configured connections and native chunk acknowledgements. World and virtual cows, player NPCs and text displays pass through real tracking; assertions do not manually pair entities or synthesize Citizens events.

Coverage includes per-viewer cancellation, retries, exactly one accepted admission per pairing, post-spawn StartTracking/render callbacks, mirrored and cosmetic equipment order and denial, live cosmetic changes, steady-state membership, range departure and denied/accepted reentry, visibility-filter removal/restoration, synchronous reentrant refresh, destruction during admission and guard cleanup. Reflection only observes native chunk eligibility and final guard state. The initial fixture incorrectly treated nested admission for a different viewer as recursion; it now checks the entity/viewer pair. The destruction scenario exposed a real stale-loop pairing defect and passes after the lifecycle repair. The cosmetic extension first failed `cosmetic_after_spawn_world minecraft:cow`, proving it catches the premature packet before the consumer repair.

Validation recorded: 2026-09-23 19:44:06 +08:00 (UTC+08:00). Broad sequential regression passes 170 packet-hologram, 78 interaction, 43 packet-viewer, 47 world-visibility, 69 metadata, 101 item-hologram, 80 text-editor and 62 removal checks, plus 107 NPC assertions and 223 general probes. After repairing the cosmetic consumer, the 108-check dedicated fixture, packet-viewer, item-hologram and general fixtures and normal test/build pass again. The final ordinary suite has 217 tests with zero failures/errors/skips. Every Gradle process was confirmed terminal before the next launch. Other broad-suite logs precede the narrow cosmetic follow-up; unrelated dedicated provider/dialogue/firework results remain historical.

Release/source jars contain the changed production implementation and exclude audit classes and fixtures. `artifacts/seen-event-validation-summary.json`, generated by `artifacts/summarize-seen-event.ps1`, records the validation sequence and source/reference/fixture/log/report/jar hashes. Citizens jar SHA-256: `dcd5478b07a794922c9687e6211706a352f3f322d340095a637aa7056e8db1cd`; sources: `0a737df483a26a62dd59e438ffc6645ab04ca2be2bf01e840b650eb9894cce87`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`; original Citizens `saves.yml` remains `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Physical-client rendering, interpolation, seats, hit testing, combined modpack/proxy acceptance, separate scoreboard visibility, renderer labels/sneaking and Paradigm placeholders remain separate work. This batch does not deploy or alter production data.
