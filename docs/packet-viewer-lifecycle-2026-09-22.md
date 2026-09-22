# Packet NPC viewer and controller lifecycle

Updated: 2026-09-22 20:38:06 +08:00 (UTC+08:00).

Packet NPCs now stop sending entity updates when a viewer leaves their tracking area, changes dimension, disconnects or becomes hidden by NPC rules. Returning viewers receive fresh pairing data. Actual respawn and relogin replace the old player object instead of leaving a UUID marked permanently linked.

## Native behavior and ownership

The previous PacketNPC code added nearby viewers to a UUID set and never reconciled that set. This retained stale ServerPlayer references and continued sending metadata/equipment updates to departed viewers. It also used an NPC target-selection helper that excluded spectators/invisible players, even though those players can observe entities.

PacketNPC now derives eligible viewers from the authoritative current PlayerList, requiring a live connection, the same dimension and the configured tracking box. It excludes NPC players and applies the NPC's visibility rules. Click-redirect helpers also honor their parent's rules; cyclic redirect graphs reject visibility. Existing linked objects absent from the current set are unpaired before newcomers are paired. The existing EntityPacketTracker/ServerEntity transport continues generating the native spawn, equipment/metadata and removal packets.

The ordinary PlayerFilter constructor previously left its predicate null, so configured UUID/group/permission rules were not evaluated by `isHidden`. Both constructors now initialize the same predicate. The packet fixture proves UUID deny/allow rules, persisted rule reload and clearing. This does not claim that vanilla world-entity tracking now consumes the visibility predicate, or that every provider-specific group/permission scenario was rerun.

Controller selection now follows the current trait set on every spawn. Adding PacketNPC before spawning no longer depends on a later type-reset command or trait load order. A live API attachment first removes the real world entity before beginning packet tracking. Removing the trait clears the virtual controller and permits a real entity to respawn; removing an already-unspawned trait does not spawn it. A deferred transition verifies the NPC still belongs to its registry, preventing resurrection after destruction. Replacing the trait explicitly clears its old tracker before its replacement pairs viewers.

## Validation and remaining work

The isolated fixture passes 43 checks over real server ticks and actual PlayerList operations. Three synthetic players cover initial pairing, native metadata/equipment updates, range changes, loaded visibility rules, a spectator with invisibility, redirects, real respawn, Nether travel, same-UUID relogin, disconnect, live trait attachment/removal/replacement and deferred cleanup. Native packet serialization runs over embedded connections; no physical client or production player data is used.

Final sequential NeoForge 21.1.248 validation passes 217 ordinary unit tests with no failures/errors/skips, 43 packet checks, 101 item-hologram checks, 62 removal checks, 107 NPC assertions and 223 general probes. No new unit tests mirror the implementation; the new coverage uses native runtime entities and connections. Normal build succeeds and release/source jars exclude runtime fixtures. Citizens jar SHA-256: `b0562f90649e538a1703f6619de9f35228e6846ee32506259241cd1cef9e16a1`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Detailed source/test/reference/log/jar hashes and unchanged original-save evidence are recorded in `artifacts/packet-viewer-validation-summary.json`. The reference is the checked-in Citizens PacketNPC/controller model; native viewer/session reconciliation is implemented directly without a Bukkit metadata registry or fake online identities.

This is a prerequisite for packet holograms, not a claim that `npc.use-packet-holograms` is implemented. Remaining work includes that setting and renderer lifecycle, per-viewer text, mounted packet hierarchies, packet player skin presentation, ordinary world-entity visibility integration and real-client/modpack acceptance. The retained Interactions/Paradigm/Economy/CMDCam stack and the [user's ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md) are unchanged. No production files were modified or deployed.
