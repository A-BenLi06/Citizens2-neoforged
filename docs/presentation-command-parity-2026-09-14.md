# Native display, interaction, boss-bar and effect commands

Implementation follow-up for 2026-09-14 (UTC+08:00), after `2c00d9e`. Reference: Citizens2 `d98e55016c2df8102f37732cd19168fe8dd27e46`.

## Restored command surface

| Command | Implemented controls |
|---|---|
| `/npc display` | Billboard, block/sky brightness, interpolation delay/duration, width/height, translation, scale, both quaternion rotations, view range and shadows. Accepts the original underscore flag names and the compact aliases printed in the original usage. |
| `/npc itemdisplay` | Item transform with both Bukkit serialized names and native enum names; completions come from available contexts. |
| `/npc textdisplay` | Authored text, alignment, line width, background RGBA color, shadow and see-through flags. Empty quoted text clears the body. |
| `/npc interaction` | Persistent size and responsiveness, a registered native `InteractionTrait`, immediate hitbox changes and name suppression. |
| `/npc bossbar` | Title, color, legacy/native styles, visibility, flags, tracking, viewing permission and range. Range changes require the separate `citizens.npc.bossbar.range` node. |
| `/npc potioneffect` | Add/list/remove named persistent effects, temporary effects, tick duration/infinity, amplifier, particles, ambient and icon settings. Types resolve against the effect registry, including installed mod effects. |

Commands enforce selection, ownership, entity type and permission requirements. They opt into strict typed argument handling. Semantic input is validated before changing traits, including brightness bounds, complete finite vectors/rotations, nonnegative sizes and effect duration/amplifier limits. Invalid operations and unknown enum/registry values fail visibly. `/npc bossbar` can attach the default bar without flags; the display/interaction configuration commands require an option.

The port now declares **191** root/modifier pairs, up from 185 in `2c00d9e` and 155 at the work-start baseline `f8ede13`. Across these two batches, 34 groups / 36 names were added. The remaining 1.21.1 `/npc` name from the source inventory is **`text`**. Six later-version entity/variant names and the two waypoint HPA debugging aliases are separate omissions. `artifacts/presentation-command-inventory.json` records the count; this does not certify complete parameter, behavior or API parity.

## Behavior repaired while exercising the commands

- The original display command accepted both rotation arguments without applying them. Both now reach the native entity. A partial transformation update reads the current transformation and preserves its unconfigured components through the native accessor, rather than resetting them to identity/default scale.
- Generic NPC name updates previously overwrote an authored text-display body on every update and after spawning/renaming. Explicit trait text now takes priority over the fallback NPC name. Existing rendered hologram components retain their priority. Interaction entities keep their names suppressed during regular NPC updates.
- NPC BossBars reconcile viewer changes without removing/re-adding every unchanged viewer on every tick. Visibility, range and live permission changes continue to apply when a tracking placeholder cannot resolve. Invisible observers can still see their HUD bar. Progress supplied by tracking/API callbacks must be finite and is bounded; failed resolution retains previous progress. Withers reuse their own bar. Despawning/removing a custom bar clears its viewers.
- Quoted empty values were skipped as if they were collapsed parser tokens, so `--text "" --shadowed false` consumed the following flag. The parser now distinguishes explicit empty values, preserves intentionally quoted whitespace and consumes each flag's own value. This also permits clearing bar flags and viewing permissions.
- Named potion effects containing a dot were saved as nested paths and lost on copying. Map serialization now encodes values separately and writes literal map keys as a map. Existing registry-key escaping remains compatible. Unit checks cover coexisting `a` / `a.b`, delegated values, YAML round trips and deletion.
- The bundled Chinese potion-removal message had malformed `{{0}}` syntax, causing an error after a successful removal. The bundled value is corrected. Existing editable malformed translations receive a warning and a per-key fallback to the base message; their files and other locale choices remain intact. The fixture retains its old malformed override and verifies successful command completion through this path.

The NPC bar controls complement the earlier [conversation BossBar implementation](dialogue-displays-parity-2026-09-14.md).

## Effect semantics and limits

Named effects are templates applied immediately and after respawn. Live duration countdown does not consume the stored template. Temporary effects apply once and are not restored after respawn. As in the reference, removing a named template prevents future applications; its already active effect retains its normal lifetime. The legacy defaults are infinite duration and amplifier 1, with ambient/particles/icon false.

An absent mod effect provider remains a separate persistence limitation of the existing effect loader; installed-provider save/copy/live behavior is verified. Full text-parser features, parameterized cloud particles, text editing, additional command parameters, API events, conversation actions/configuration and complete client/modpack acceptance remain open. State/packet checks do not establish physical-client typography or every borrowed End-dragon boss-bar lifecycle. CmdCam remains responsible for camera playback; Yuuniverse Economy remains responsible for economic APIs.

## Validation

The isolated entity-command fixture passes **285 checks**, retaining the prior 190 and adding 95 for these commands. It verifies saved/copied/live/respawned state, actual interaction AABBs, display transformation fields, authored text across updates/renames, potion lifetimes, installed custom effect IDs, strict rejections, registered/non-operator permissions, BossBar packets and cleanup. Its successful log is `artifacts/repair-presentation-commands-runtime248-format.log`.

All final gates pass: 285 entity-command checks, 45 dialogue-display checks, 107 NPC scenario assertions, 223 existing runtime probes, 44 removal checks, 101 actual-provider/restart permission checks, 18 no-provider checks, 48 dialogue unit cases and 163 ordinary tests. Unit failures/errors/skips are zero. The normal build succeeds and release jars exclude `RuntimeAudit` classes. `artifacts/presentation-command-validation-summary.json` records the logs and jar hashes.

The fixture setup and original command are documented in [the entity follow-up](entity-command-parity-2026-09-14.md). All validation targets NeoForge **21.1.248** and stays in isolated workspace fixtures. No production deployment or server configuration migration is included.
