# Interaction label attachments and mixed passenger traversal

Updated: 2026-09-23 20:41:30 +08:00 (UTC+08:00).

Interaction holograms retain native Interaction entities and mounts. Their label height now follows the parent's native height plus the authored line offset, accounting for the actual passenger seat. The fix also restores native traversal for real passengers beneath virtual NPC roots, a missing path exposed by the label audit.

## Native label geometry

Minecraft 1.21.1's Interaction width/height callbacks update its bounding box, while ordinary name rendering reads `Entity.getAttachments().NAME_TAG` from cached dimensions. Sending height alone therefore leaves the name attachment stale. The native client applies each metadata entry through `SynchedEntityData.assignValues`, invoking callbacks in packet order even for unchanged values. The pose callback rebuilds cached dimensions and attachments.

For an Interaction hologram, `HologramMetadata` now sends width and height before the actual pose. Incoming shape/pose fields are preserved; absent fields use the entity's current data. The pose is not changed to an unrelated state. The existing fresh packet overlay still supplies per-viewer text and sneaking independently. Unrelated NPCs and ordinary Interaction entities retain their native packet paths.

`HologramRenderer.onPreSpawn` initializes a newly created helper before world insertion or packet pairing. The Interaction renderer mounts there so native passenger-slot selection is available, then calculates the height from the parent's native height, authored offset and actual riding position. The shared entity refreshes its dimensions directly. A failed initializer discards the unpaired entity and its mount before propagating the failure, allowing a later spawn retry.

The renderer recalculates geometry when its position/height changes. The reference's narrow 0.05-block width is retained; signed native heights preserve negative line offsets instead of clamping all labels to the mount plane. Native horizontal seat behavior is retained. This change does not replace the renderer with a display entity or add a client dependency.

## Real passengers beneath virtual roots

Native level iteration skips an entity that already has a vehicle; the vehicle normally reaches it through `ServerLevel.tickPassenger`. A virtual root is absent from that iteration, so real passengers beneath it previously received no passenger tick. This left a world hologram at an old position when its parent switched to packet transport.

The level hook now takes a snapshot of live virtual roots after native entity iteration and enters the existing passenger traversal for each eligible root. It checks current lifecycle/vehicle/dimension state, native entity-ticking position and tick-freeze state. It never ticks the virtual root itself. The existing virtual-passenger hook positions intermediate virtual nodes and delegates their real children back to native traversal, preserving the native world-membership check, riding tick and passenger recursion. Roots already attached beneath a world entity are not traversed a second time.

## Validation and limits

The [isolated audit](../tools/interaction-label-runtime-audit/README.md) passes 393 checks, including every captured shape packet's ordering and height. It covers native first pairing, world and virtual helpers, independent viewer text/sneaking, movement, actual scale changes, negative margins, spacing, visibility/range reentry, camel seats, virtual-parent conversion and movement, respawn and cleanup. Failed initializer cleanup/retry is exercised. Real passengers directly under a virtual root and below an intermediate virtual node tick once per level tick; virtual nodes remain unsimulated, and freezing the root stops native passenger traversal.

Captured outgoing metadata is replayed into unregistered native Interaction entities using the same callback as the client. The fixture does not call `refreshDimensions` or repair replica state. It inspects both server attachments and replayed attachments rather than inferring label placement from a height packet alone. A packet-focused check also preserves an incoming non-default pose/shape and immutable input. Replicas use the loaded server level without world insertion; Interaction's native `noPhysics` flag excludes the server-only collision adjustment in dimension refresh.

Sequential NeoForge 21.1.248 regression passes 179 metadata checks, 170 mount checks, 108 tracking-admission checks, 78 packet-interaction checks, 107 NPC assertions, 223 general probes and 217 ordinary tests with zero failures/errors/skips. Each Gradle process exited before the next began; the final process exited 0. Normal build and release/source exclusions pass, including the native shape/pose access transformers and passenger mixin. The project default NeoForge version is unchanged.

Citizens SHA-256 is `1e97e17075d6270d9f201663c77d59b83f9e8123eaaacd03956d880b05afd28a`; sources are `ff2c8aeaf75508d77e0ccbb98012365182567fa28118e9afc4f00ba65e711168`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Detailed final gates, source/native/fixture/log/report hashes and the unchanged original-save check are recorded in `artifacts/interaction-label-validation-summary.json`.

The first run exposed the missing native traversal for a real helper beneath a virtual parent; the successful expanded audit includes the resulting fix. The final dedicated run recorded 393 assertions. Its total includes per-packet checks and may vary with native packet timing; every scenario and every captured shape packet must pass. Unrelated dedicated suites retain historical evidence.

These checks establish native metadata geometry and the tested server traversal. Physical-client drawing, horizontal seat appearance, interpolation, click targeting, arbitrary modded vehicles and combined modpack/proxy acceptance remain unverified. The [ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md) remains authoritative: retained providers and production files are unchanged, and no deployment is included. The goal remains active.
