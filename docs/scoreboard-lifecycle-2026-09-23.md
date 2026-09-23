# Native NPC scoreboard lifecycle

Updated: 2026-09-23 21:04:19 +08:00 (UTC+08:00).

NPC teams now follow the receiving client's play session. Reconnecting with the same UUID receives a fresh team, while native player respawn and dimension travel retain the existing team. Entry changes replace membership, and despawn, trait replacement, disabled teams and failed spawning remove both client state and the trait's private native team.

## Native behavior and implementation

Minecraft 1.21.1 keeps its client scoreboard in `ClientPacketListener`, independently of the local player and client level. Native respawn replaces `ServerPlayer` but retains its play listener. The normal `PERFORM_RESPAWN` packet handler assigns the replacement back to that listener. Citizens therefore tracks `ServerGamePacketListenerImpl` identity and the last property revision delivered to each session, pruning listeners that no longer belong to the current player list.

The former UUID cache could suppress the first ADD after reconnect. A single global changed flag was also insufficient once one recipient could receive current properties during pairing. Per-session revisions let that recipient receive its update early without losing updates for everyone else, and suppress unchanged packets.

Native `Scoreboard.addPlayerTeam` returns the existing team if its name is already registered. Merely clearing the trait's `team` reference therefore left old members in the private scoreboard across recreation. Cleanup now calls native `removePlayerTeam`, which also removes its membership mappings. Changing a live entry removes the old client team before updating private membership and delivering a fresh ADD; a properties-only packet cannot replace members.

`ServerEntity.sendPairingData` now prepares attached scoreboard traits before player-profile and entity packets. Name visibility, collision rules and glow color are present in the first ADD. Normal updates still distribute teams to all real online players, including filtered, distant and other-dimension players, as the reference Citizens implementation does. NPC entity visibility does not revoke a global scoreboard team. The private teams never enter the server's authoritative scoreboard.

Both `RESET` and null restore native default color. Formatting styles remain invalid, and persisted style-only values are sanitized during trait load. `/npc glowing --color reset` accepts the same native reset value. Native spawn insertion can pair viewers before `NPCSpawnEvent` rejects the spawn, so failed/canceled spawning explicitly disposes any prepared team before discarding the entity.

## Validation

The [isolated audit](../tools/scoreboard-runtime-audit/README.md) passes 312 checks on NeoForge 21.1.248. Three synthetic players use real `PlayerList` admission, protocol encoding, native tracking and chunk acknowledgements. Actual team packets are replayed into native `Scoreboard` and `PlayerTeam` using the client handler's action/property/membership order. Unknown-team updates and duplicate ADDs fail the audit.

Coverage includes world/virtual cows and players, first team/profile/entity ordering, global distribution despite entity filters/range, unchanged suppression, preparing one viewer ahead of global updates, property updates, null/reset/style handling, persisted styles, real commands, live entry replacement, NPC rename/respawn, disable/enable, same-UUID reconnect, native player respawn requests, dimension travel, filter reentry, trait replacement, canceled spawn/retry and destruction. Read-only reflection verifies private team/member/session cleanup. An independent operator team survives every scenario and Citizens never adds its private teams to the server scoreboard.

During fixture development, `getTrackedPlayers` was found to enumerate score holders rather than team members; the assertion now observes the actual membership map. Respawn uses the real inbound request handler, including its listener-player reassignment. The final fixture passes without altering client replica state outside packet replay.

Sequential regression passes 108 tracking-admission, 179 metadata, 393 Interaction-label, 101 item-hologram and 47 world-visibility checks, 107 NPC assertions and 223 general probes. Normal tests/build pass 217 tests with zero failures/errors/skips. Every Gradle process exited before the next started, and the final process exited 0. Release/source jars exclude audit and provider classes. Expected unavailable-item and intentional transaction-failure diagnostics remain confined to their existing regression scenarios.

Citizens SHA-256 is `5e2ea701f6cff46ace8d3c04d4fbdd7cc7dd7accb227775ce87f506736aac1aa`; sources are `8790c50bdbdd5c8c4b50db6304014be0162a842fb0e84710e0a7661aa94256ef`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Full results, source/fixture/log/report hashes and original-save/provider checks are recorded in `artifacts/scoreboard-validation-summary.json`, generated by `artifacts/summarize-scoreboard.ps1`. Native source excerpts and archive hash are in `artifacts/scoreboard-native-source.txt`.

Physical-client drawing, mirrored-name presentation, arbitrary modded clients, combined retained-provider/modpack behavior and proxy acceptance remain outside this fixture's evidence.

The [user-selected ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md) remains authoritative. No retained provider, production data, project default NeoForge version or deployment is changed. This is one validated Citizens lifecycle batch; the wider goal remains active.
