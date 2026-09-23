# Per-viewer hologram text metadata

Updated: 2026-09-23 10:45:32 +08:00 (UTC+08:00).

Later follow-up: [per-viewer sneaking](hologram-sneaking-2026-09-23.md) extends this metadata boundary and the fixture to 179 checks. The results below record the original text batch; current scope and other completed follow-ups are tracked in [the parity status](npc-parity-status-2026-09-13.md).

Hologram text now resolves for the receiving player in both world and packet NPC transports. A viewer's name or provider value is written into a fresh native metadata packet; it never overwrites the shared entity or the packet sent to another viewer. Unchanged templates can refresh dynamic values, and blank viewer-specific names hide their nameplate.

## Reference and native behavior

The checked-in reference `PacketEventsHook` rewrites hologram custom-name/text-display metadata through `HologramRenderer.getPerPlayerText`. The port already exposed that renderer method, but its native packet paths did not call it. Adding the missing packet-hologram configuration alone would therefore still show unresolved or shared text.

`HologramMetadata` supplies the missing native boundary. Pairing bundles are personalized before `ServerEntity.addPairing` sends them. Ordinary tracker broadcasts and the existing `EntityPacketTracker` personalize subsequent metadata. Native typed accessors identify custom names, name visibility and text-display text; there are no hardcoded protocol field numbers. Unrelated fields remain in the packet.

Built-in line renderers resolve authored NPC placeholders against the owning parent, while the packet retains the helper entity's ID. Custom renderer implementations still receive the hologram NPC through the existing API. The shared entity retains its ordinary unresolved viewer template, so server-side persistence and other viewers cannot inherit a player's private text.

The existing hologram update cadence also refreshes the actual linked/native viewers. Cached values suppress unchanged sends. Refresh rejects hidden, disconnected, retired and other-dimension players. Packet reconciliation and supplemental text updates use the same live eligibility predicate, including range, so an edit cannot send text to a departed viewer before the next helper tick. Native unpairing removes a viewer's cache, and helper destruction removes its entity entry; weak entity keys provide an additional lifetime bound.

## Validation

The [isolated audit](../tools/hologram-metadata-runtime-audit/README.md) passes 69 checks with real PlayerList admission, actual chunk packets, native pairing and protocol encoding. It covers world displays, world armor stands and an explicit PacketNPC text renderer, including different player/NPC/provider values, dynamic updates, immutable shared packets/entities, blank text, filter exclusion, same-tick range departure, re-entry and lifecycle cleanup. A non-NPC text display remains unchanged.

Sequential NeoForge 21.1.248 regression gates pass 47 world-visibility checks, 43 packet-viewer checks, 101 item-hologram checks, 80 text-editor checks, 62 removal checks, 107 NPC assertions, 223 general probes and 217 ordinary tests with zero failures/errors/skips. After adding the same-tick eligibility check, the metadata and packet suites and normal test/build were rerun. Every Gradle process was terminal before the next launch.

Normal build and release/source exclusions pass. Both jars contain the metadata implementation, and the release includes all three native accessors. Citizens SHA-256 is `72183d48667807cdebb83d75b984b28c1c070508874be6466a5b1d8e7a1958a9`; Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Detailed source/reference/fixture/log/report/jar hashes and the unchanged original-save check are in `artifacts/hologram-metadata-validation-summary.json`.

## Remaining work

This is the text transport prerequisite for packet holograms. The global packet-hologram setting, mounted virtual hierarchies and virtual-entity click routing remain separate implementation work. Renderer-specific presentation such as interaction-entity labels and per-viewer sneaking is not established here. Paradigm placeholder registration/resolution also remains a separate native service boundary; this batch uses the existing Citizens registration API.

Physical-client rendering, target-modpack compatibility and proxy acceptance remain unverified. Scoreboard/team packets and seen-event cancellation retain their separately documented limitations. Retained provider ownership follows the [ecosystem guidance](neoforge-ecosystem-replacements-2026-09-22.md); no production deployment or provider replacement is included.
