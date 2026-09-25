# Private rotation delivery through native tracking

Minecraft 1.21.1 / NeoForge 21.1.248. Scope follows [the ecosystem guide](neoforge-ecosystem-replacements-2026-09-22.md).

Reviewed: 2026-09-25 11:56:32 +08:00 (UTC+08:00).

## Behavior

Private rotation now has one selected owner per viewer: an active UUID override, then the first accepted general session. Interpolation advances each registered session once; only the winning result is delivered. Delivery is remembered per actual ServerPlayer identity and quantised angle tuple. This fixes the reproduced omission of an initial zero-angle target, overlapping sessions sending competing angles, and unchanged sessions failing to initialise later viewers.

Native world trackers and virtual EntityPacketTracker broadcasts pass through the same rotation projection. It handles body/pitch rotation, relative movement with rotation, head rotation, teleport and the initial spawn packet, including bundles. The player-NPC `/npc rotate` command uses the same projection and current native viewer membership. Position-only and unrelated-entity packets keep their original values. Supplemental rotation packets require actual pairing; viewer invisibility or spectator mode alone does not disqualify a player who can still see the NPC.

Every projection creates a per-viewer packet. Native movement constructors preserve entity IDs, movement deltas and on-ground flags. Spawn and teleport packets are copied with their native 1.21.1 stream codecs; targeted accessors change only angle bytes on those copies. Spawn UUID/type/data, exact encoded velocity, coordinates and all other fields therefore survive without floating-point requantisation. The shared packet remains available unchanged for viewers without an override.

A pairing packet contains the current private body, pitch and head angles. It counts as initial delivery, so a stable session needs no redundant correction on its next tick. Unpairing drops that viewer's delivery record. Head-only sessions start from entity yaw, matching the client's native orientation rather than the distinct living-body animation yaw.

## Ownership and lifecycle

Replacing one UUID in a session covering several viewers leaves the other UUIDs attached to their existing owner. Resetting a UUID removes only that override and reveals any accepted general session. Releasing an owner immediately delivers an already-running fallback's current angles, or restores native entity yaw/pitch/head if no private owner remains. Releasing an older owner does not reset a newer UUID owner.

Explicitly ended sessions never run again. A naturally completed finite session delivers its final frame for that tick and yields to the fallback on the following tick. Clearing sessions, despawning, removing the trait and replacing the trait release registered owners and delivery records. The physical rotation request is cancelled during trait retirement. Freshly spawned entities do not inherit the old entity's private sessions.

Selection bounds recursive predicate queries and rejects decisions made stale by ownership changes inside a predicate. Delivery rechecks the entity and native viewer admission after selection. The original `onPacketOverwritten()` API remains a compatibility notification with no global resend state: native projection and per-viewer delivery now maintain the actual state. The reference hook calls that method after rewriting; its previous lack of callers was not itself evidence that its recording semantics were wrong.

## Validation

The isolated loopback fixture uses port 25611, real PlayerList admission, native chunk-batch acknowledgements and world/virtual pairing. Its 64 checks cover initial zero angles, overlap and UUID precedence, shared ownership, fallback/reset/end behavior, finite sessions, head-only orientation, filter changes and reentry, late pairing, native world and virtual broadcasts, exact non-angle fields, original packet preservation, the real player-NPC rotate command and trait lifecycle. Real native tracking updates run across multiple ticks while physical angles differ from the private view.

A negative control on implementation base `1e826f5` fails with `AssertionError: initial_zero_angles_are_sent`. The focused check was run before the implementation changes; the expanded fixture retains that check. Evidence is retained in `artifacts/rotation-negative248.log` and `artifacts/rotation-dedicated248.log`.

```powershell
./tools/prepare-rotation-audit.ps1
$env:JAVA_HOME = 'C:\Users\ben_l\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/rotation-runtime-audit.gradle runServer --console=plain
```

Sequential regression passes 63 LookClose, 81 movement/player-list, 108 tracking-admission and 43 packet-viewer checks, plus 107 NPC assertions and 223 general probes. All 232 ordinary tests pass with zero failures/errors/skips. The final inactive-session fast path avoids additional viewer scans for NPCs with no private state; dedicated validation and the normal build are repeated after that change. Normal binary/source archives exclude opt-in audit and provider fixtures. Detailed source/reference/native archive/log/report and jar hashes are recorded in `artifacts/rotation-validation-summary.json`.

The clean build changes the Interactions jar archive timestamps. Its complete entry-name set and every entry's bytes match the retained reference jar with SHA-256 `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`; there are no dialogue content changes. The original Citizens save retains SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

## Boundaries

This implements the native tracker paths and Citizens' direct rotate-command packets. It does not install a global interceptor for arbitrary packets an external mod might send directly to a connection. Physical-client interpolation/rendering and combined modpack/proxy acceptance remain unverified. Remaining LookClose command options, persisted string-filter behavior and NPC-targeting controls are separate work. No provider was installed and no production deployment occurred. The repository's declared NeoForge version remains 21.1.233; 21.1.248 is the explicit local validation override.
