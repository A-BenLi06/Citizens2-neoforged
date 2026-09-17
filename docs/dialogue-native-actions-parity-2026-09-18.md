# Native dialogue actions and parameter semantics

Updated: 2026-09-18 01:06:20 +08:00 (UTC+08:00). Implementation base: `c61cbd0`.

## Reference and findings

Compared the actual Interactions 2.14.1 `ActionUtils` bytecode with the original server's Bukkit/CraftBukkit implementations of potion effects, sound playback, titles, colors and fireworks. The plugin SHA-256 is `ce4824a2bb4317eb9acd40cd4388eebedfab521d96310bf74f2d26f0ac85293e`. The server API reference is `.arclight/mod_file/arclight-1.20.1-1.0.6-SNAPSHOT-6859fcb.jar` under the original server directory.

Before this change, the port recognized seven of the original eighteen action verbs. It also interpreted potion duration as seconds, used the configured level directly as an amplifier, and reversed the optional particles flag. Teleport coordinates passed through floats, and several malformed numeric fields were silently replaced with defaults during execution. Title parameters and sound volume/pitch were not fully checked before earlier actions could consume payment.

The original configuration contains 76 single-line potion actions, including `BLINDNESS;30;5;true`: its intended effect is 30 ticks, amplifier 4, visible particles and icon. The old port instead applied 600 ticks, amplifier 5, with particles and icon hidden.

## Implemented behavior

| Action | Native behavior |
|---|---|
| `give_potion_effect` | Three required fields: effect, ticks, one-based level. Optional particles defaults to true. Ambient is false; the icon follows particles, as in the original five-argument Bukkit constructor. Native infinite duration `-1` is retained. Nine renamed Bukkit effect aliases resolve to their actual registry IDs; namespaced IDs remain explicit. |
| `remove_potion_effect` | Resolves the same names/IDs and removes that effect. Removing an already absent effect succeeds. Unknown effects fail preflight. |
| `teleport` | Requires world, x, y, z, yaw and pitch. Coordinates retain double precision; rotations use finite floats. World-name resolution remains a separate open issue below. |
| `title` | Requires integer fade-in/stay/fade-out ticks and both text fields. Exact `none` clears either line. Native `-1` timing sentinels remain supported. Formatting/placeholders are retained, and the subtitle packet precedes the title packet. |
| `playsound` | Requires a registered sound plus finite volume/pitch. Bukkit sound constants resolve through the registry; playback is private, at the player's position, on MASTER. |
| `playsound_resource_pack` | Uses a direct sound holder for a valid resource identifier, including identifiers absent from the server registry. The client/resource pack owns its audio content. |
| `stopsound` | Stops a registered sound on MASTER; exact `all` stops every sound/category. |
| `stopsound_resource_pack` | Stops the supplied resource identifier on MASTER without requiring a server registry entry. |
| `player_command` | Uses the actual player's command source and permission checks, including after alias expansion. Saved-item, economy and shop backend interceptions are reserved for the existing privileged action forms. If a corresponding native command is installed, the ordinary action reaches that command's own dispatcher/authorization. |
| `firework` | Parses `colors:RED,GREEN type:BALL_LARGE fade:AQUA,ORANGE power:2`. Spawns an actual native rocket at the player, with one explosion, the original seventeen Bukkit RGB colors, five shapes, optional fade colors, and power 0–127 (default 0). Trail and flicker are false. Invalid definitions fail before spawning. |

`console_command`, `player_command_as_op` and `remove_item` retain their existing behavior. This brings the recognized original action inventory to **13/18**; it is not a behavioral completion percentage.

Preflight and execution share strict parameter parsing. Missing fields, nonintegral integer fields, overflow, non-finite coordinates/rotations/sound values, invalid booleans, unknown effects/sounds/colors/shapes and invalid firework fields reject a batch before earlier item payments execute. This intentionally replaces silent fallback, native clamping and ignored typos. Potion levels must fit the target's native 1–256 range. Sound volume/pitch cannot be negative. The existing extension allowing literal semicolons in the subtitle is retained; the original plugin discarded fields after the fifth.

## Verification

All gates passed on NeoForge **21.1.248**, JDK 21:

- **91 native-action runtime checks**, using connected players, actual permission-aware dispatch, native potion ticks and rocket movement, and outgoing packet inspection/codec round trips. Tests include private presentation, registry-independent resource-pack sounds, all nine renamed potion aliases, valid privileged rewards, ordinary command/alias privilege boundaries and 34 invalid batches preserving payment/reward/position.
- **127 existing dialogue-display checks**, including real respawn, inline options, animation, interruption, input and display lifecycle.
- **60 dialogue unit cases**, **107 NPC assertions**, **223 general runtime probes**, and **166 ordinary tests**. No unit failures, errors or skips; normal build successful.
- Release jars contain no opt-in runtime probes. The normal Interactions jar SHA-256 is `b4985845e234c369704ec3fccdd70fecdcc3797cd20036b879974059564142b0`.
- A read-only scan extracted 8,285 single-line action entries from the original YAML: 4,145 sounds, 3,013 titles, 51 teleports and 76 potion actions. Their field counts and numeric syntax pass the new rules. This scan is not whole-YAML/provider validation or proof of complete conversation execution.

Evidence: `artifacts/native-actions-validation-summary.json`, `native-actions-dialogue-unit-results.json`, `native-action-config-syntax.json`, and the `repair-native-actions-*248.log` files. Reference disassembly is retained in `legacy-action-utils.txt`, `legacy-native-action-semantics.txt`, `legacy-title-action.txt` and `legacy-action-schedules.txt`. Unrelated dedicated entity/pickup/movement/text/provider/removal suites were not rerun for this batch.

## Remaining work

Five original actions remain unimplemented: timed `actionbar`, `wait`, `wait_ticks`, `influence`, and `send_to_server`. The reference ActionBar action has its own tick-based refresh/clear schedule, distinct from the implemented conversation-status ActionBar. The two wait actions schedule the remaining batch independently of the dialogue timer. Their lifecycle/failure semantics must be implemented without blocking the server thread or treating deferred work as completed. Influence needs conversation-entity persistence/API/placeholder behavior; transfers need an actual destination/provider model.

Review also confirmed that `Worlds.resolve` currently falls back to the overworld for unknown names, including unknown namespaced dimensions. That behavior predates this batch and still needs explicit world-alias configuration and rejection of unresolved worlds. Coordinate precision tests do not establish complete world-resolution parity.

Dialogue authoring/admin/configuration, incoming-chat restoration, quests and saved-item fidelity, group mutation capabilities, remaining Citizens/API/command behavior, Sentinel and physical-client/modpack acceptance remain open. Camera playback stays with CmdCam and economic APIs with Yuuniverse Economy. No production files or deployment were changed. The full parity goal remains active.
