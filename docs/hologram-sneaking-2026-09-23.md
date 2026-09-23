# Per-viewer hologram sneaking

Updated: 2026-09-23 20:17:58 +08:00 (UTC+08:00).

The native metadata path now consumes `HologramRenderer.isSneaking` for each receiving player. It sets or clears only the native shift bit, independently of the renderer's text override. This restores a Citizens renderer contract within the [retained ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md).

## Behavior

`HologramMetadata` uses Minecraft's typed shared-flags accessor and shift-bit constant. When an outgoing packet already contains shared flags, those flags supply every other bit; otherwise the overlay starts from the entity's current flags. Each viewer receives a new packet. The shared entity, input packet, other metadata fields and entity pose are preserved. Returning `null` from `getPerPlayerText` leaves the packet's text alone and still permits a sneaking override.

Pairing bundles, ordinary native broadcasts and virtual NPC metadata use the same overlay. If a pairing bundle has a spawn but no shared metadata, the viewer's override is inserted after that spawn. The existing configured hologram refresh applies changing viewer values even when the authored text remains unchanged. Unchanged values are suppressed; actual viewer membership, current visibility, connection identity and packet range continue to govern delivery and cache cleanup.

Name helpers copy their parent's shift state on every trait tick. A stationary parent's flag can change without changing its position, dimensions or pose, so copying only when the renderer moved left the name stale. This applies to world and virtual helpers.

Minecraft 1.21.1's `Entity.isDiscrete()` reads the shift flag, and its native name renderer uses that state when selecting the name's drawing modes. Text displays also receive the API's shift flag, but retain their separately configured display flags; this does not reinterpret their `seeThrough` setting or claim a crouching entity pose.

The reference `main/.../PacketEventsHook.java` consumes the same renderer method but only ORs in a true flag on a metadata packet that already contains flags. The native implementation honors both boolean values and supplies missing pairing metadata so a false result can clear the viewer's previous sneaking state.

## Validation

The expanded [isolated metadata audit](../tools/hologram-metadata-runtime-audit/README.md) passes 179 checks. It retains the text/lifecycle checks and adds world/packet armor stands and text displays with different states for two viewers. It exercises initial pairing, true/false refresh, null text, preserved native flag meanings, immutable packets, non-NPC controls, suppression, filter/range departure and reentry, stationary names and destruction/respawn cleanup. Players enter the actual PlayerList, receive encoded packets and acknowledge native chunk batches. A focused packet-shape check also covers a pairing bundle without metadata.

Sequential NeoForge 21.1.248 regression passes 170 mount checks, 108 tracking-admission checks, 101 item-hologram checks, 107 NPC assertions, 223 general probes and 217 ordinary tests with zero failures/errors/skips. Each Gradle process exited before the next started. The final process exited 0; no audit task remains running. The project default NeoForge version is unchanged.

Normal build and release/source exclusion checks pass. The release includes the native flag access transformers; audit classes remain excluded. Citizens SHA-256 is `ede945ebf3bd271f9a7668ea9eb952edfd11cae9f3f9f6b0afe7904047422c64`; sources are `8629b4795ee3f9aa37ef0d9194d2d0ee42acc1338fd968918e7a73ff4c208660`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Logs, source/reference/fixture/report hashes and the unchanged original-save check are recorded in `artifacts/hologram-sneaking-validation-summary.json`.

The fixture's initial conversion check retained a removed world entity; the corrected check reacquires and verifies the live virtual replacement before toggling the parent's flag. Production behavior did not change in response to that fixture failure. Unrelated dedicated suites retain their historical evidence.

## Interaction label finding and remaining acceptance

Read-only inspection of the actual transformed 1.21.1/NeoForge 21.1.248 sources found a separate Interaction label problem. `Interaction.onSyncedDataUpdated` updates the bounding box for width/height changes, while `Entity.getAttachments()` reads cached dimensions. The native `NoopRenderer` inherits ordinary name rendering, which uses the `NAME_TAG` attachment rather than the current bounding box. Thus changing Interaction height alone does not establish the intended label offset. The implementation comment now records this limitation; label positioning remains open. Copying the newer reference bridge's height packet would not establish a correct 1.21.1 fix. Source excerpts and their archive hash are in `artifacts/hologram-sneaking-native-source.txt`.

Physical-client rendering, Interaction label positioning, full modpack compatibility and proxy acceptance remain unverified. Retained providers and production files are unchanged, and no deployment is included. The Citizens port goal remains active.
