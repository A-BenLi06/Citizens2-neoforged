# Native NPC text editing and lifecycle

Implementation follow-up for 2026-09-14 (UTC+08:00), after `64d25e5`.

## Reference and command coverage

Compared `EditorCommands`, `TextBasePrompt` and `Text` at Citizens2 `d98e55016c2df8102f37732cd19168fe8dd27e46`, and inspected the original installed Citizens 2.0.32 jar where relevant. The old implementation uses Bukkit Conversations and a player editor together. The port had the stored text/backend but no command or text-editor prompt.

`/npc text` now opens/closes a native editor. It accepts chat input and command buttons, and an initial invocation can open the editor and process an optional operation. Operations are:

| Input | Behavior |
|---|---|
| `add <text>`, `edit <index> <text>`, `remove <index>` | Modify zero-based entries. Empty entries and edits are supported. Chat preserves authored markup, spacing and flag-like text. Quote flag-like content in slash commands. |
| `page <number>` | Paginated entries with edit/remove controls; removal of the last page returns to a valid page. The heading identifies the bound NPC. |
| `delay <ticks>` | Set the interval; `-1` restores the global randomized delay. |
| `range <blocks>` | Set the proximity radius; negative input clamps to zero as in the reference, nonfinite input is rejected. |
| `item <pattern>` | Set the item-in-hand pattern; `default` restores the configured global pattern. |
| `random`, `close` / `talk close` | Toggle randomized line choice or proximity speech. |
| `speech bubbles`, `speech bubbles duration <duration>` | Toggle temporary holographic lines and set their lifetime in native duration/tick syntax. |
| `realistic looking`, `send text to chat` | Toggle line-of-sight gating and chat delivery. |
| `exit`, repeating `/npc text` | Leave the editor and its prompt. |

The editor enforces `citizens.npc.edit.text` and NPC ownership. It stays bound to its original NPC when selection changes. An explicit conflicting `--id` / `--uuid` is rejected; a matching explicit target remains valid. Unknown value flags and invalid indices/pages/numbers/durations do not silently truncate input or mutate configuration. Feedback and controls use message resources.

The inventory is now **192 port / 193 reference root-modifier pairs**. The remaining eight reference pairs are six later-version entity/variant commands and `waypoints hpa` / `wp hpa`; seven port-only pairs explain why subtracting totals is misleading. Every reference `/npc` name applicable to 1.21.1 is now declared. This does not prove every command parameter or backend behavior is complete. Evidence: `artifacts/text-editor-command-inventory.json`.

## Lifecycle and behavior repairs

- The text prompt checks the bound registry/NPC/trait identity, permission and owner before every edit. Losing permission/ownership closes editing. Removing the NPC or its text trait closes the editor immediately. Queued input is bound to the original prompt object, so it cannot edit a replacement prompt/trait.
- The text session follows the replacement `ServerPlayer` after actual respawn. Commands continue to work without requiring a new NPC selection. Logout, reload, global cleanup, editor toggling, escape input and failed initial prompt rendering release both editor and prompt state.
- Prompt replacement runs the previous abandonment callback. Session-specific abandonment and editor-specific removal prevent a callback from closing a newly opened session. Callback registration can occur before the first prompt is rendered. Shared lookup maps support off-thread chat capture; mutations run through the server scheduler.
- An active prompt owns its chat input, so a waypoint editor does not also consume the same input. Restored `/npc path ...` forwarding to an active waypoint prompt; the original port returned early and discarded this command input.
- `ArrayTraitLookup.remove` cleared its presence bit but retained the trait reference. Consequently `hasTrait` said false while `getTraitNullable` and `getOrAddTrait` returned the removed object. Removal now clears both views. The runtime check verifies a new trait on reattachment, without changing the fixture to reuse stale state.
- Trait removal also called `isRunImplemented()`, which itself invokes `run()`. Removing a proximity text trait could therefore emit another line. Removal now directly removes the runnable without running the trait again.
- Text's advertised realistic-looking setting was stored but unused. It now gates speech through native line-of-sight checks. Proximity queries also enforce the configured spherical radius instead of including out-of-radius diagonal positions in the broad-phase box query.
- The native item registry returns air for an unknown ID. Hand-item matching now checks that a configured ID is actually registered before comparing it with the held item, preventing an unavailable item from matching empty hands. An explicit `minecraft:air` pattern still works.

## Validation and limits

The dedicated fixture checks actual commands and chat events with connected synthetic players, ownership/permission changes, prompt replacement, pending input, pagination controls, text/settings copies, actual respawn and file save/reload. Playback checks cover item gating, sequential/wrapped/random choice, positive cooldown, temporary bubble persistence/expiry, a native occluding wall and exact range boundaries. It also verifies that removing a live trait does not run its behavior again.

The final dedicated fixture passes **80 checks**, including waypoint-prompt command routing and unavailable/explicit-air item matching. Its log is `artifacts/repair-text-editor-runtime248-item-filter.log`. Three new unit cases cover payload preservation and duration bounds.

Regression validation passes 285 entity-command checks, 45 dialogue-display checks, 107 NPC assertions, 223 runtime probes, 44 removal checks, 101 actual-provider/restart permission checks, 18 no-provider checks, 48 dialogue unit cases and 166 ordinary tests. Unit failures/errors/skips are zero. The text, NPC and normal test/build gates were repeated after the final hand-item guard. Normal jars exclude runtime probes. `artifacts/text-editor-validation-summary.json` records the evidence paths, scope and jar hashes.

Run from the repository root:

```powershell
./tools/prepare-text-editor-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/text-editor-runtime-audit.gradle runServer --console=plain
```

The fixture is `artifacts/text-editor-audit-server`, binds to `127.0.0.1:25586`, requires its marker and an empty NPC registry, and shuts itself down after the checks. Use JDK 21. Normal release jars must exclude its runtime probe classes.

This work restores native text authoring and the verified playback/settings paths. Inline item holograms, the complete text parser, anvil-style entry, other API/behavior gaps, Interactions authoring/actions/configuration, group-provider limitations, quests/items, full Sentinel and physical-client/modpack acceptance remain open. Packet/event checks are not a signed-chat/client screenshot comparison. Camera playback remains CmdCam-owned and economic APIs remain Yuuniverse Economy-owned. No production files or deployment were changed.
