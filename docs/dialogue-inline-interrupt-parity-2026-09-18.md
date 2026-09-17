# Inline dialogue choices and interruption actions

Implementation follow-up for 2026-09-18 (UTC+08:00), after `68d8e29`.

## Reference behavior

Inspected Interactions 2.14.1's `ConversationConfigsManager`, `ConversationManager`, `PlayerConversationManager`, `PlayerConversation`, command handler and conversation-end event listener. Both `options_in_dialogue` and `interrupt_actions` belong to a conversation node, not an individual dialogue line or the entire NPC definition. They default to false and an empty list.

`%option_N%` means the Nth eligible option, not the source option's key. The final dialogue can display these labels before its duration ends. Input remains gated by `waitingForOptions`; the original command is `/interactions useoption N`. Inline mode suppresses the separate options block. Movement/scroll selection redraws the dialogue with the selected option format.

The original interruption listener executes the current node's actions for every end reason except `DIALOGUE`: command termination, radius exit, disconnect and server stop. It does not substitute the current line's `last_actions`. No nonempty interruption lists or enabled inline-option keys were found in the inspected original conversation files; these are optional original capabilities.

## Implementation

- Load both node settings. Normal completion continues to use `end(true)`; other existing termination paths use `end(false)` and execute the current node's interruption batch once. Display/input ownership is released before interruption commands run, so teleportation and reentrant termination do not retain movement restrictions or repeat actions. Invalid interruption batches use the existing action preflight/failure reporting. Already executed action side effects are not automatically rolled back.
- Resolve inline labels against eligible options from the applicable route, including `start_options` on another node, random dialogue and the last completed body when later lines fail requirements. Labels honor the existing normal/selected formats, player placeholders, click setting and hover text. Rows whose option markers all refer to unavailable positions are omitted.
- Refresh the full body after completion actions, when the final eligible option list becomes authoritative. Inline packet commands include a session/view identifier; old preview or previous-session buttons cannot select a newly renumbered option. Typed choices and both `/interactions choose N` and `/interactions useoption N` remain available. Requirements are rechecked at input and execution.
- Support multiple markers and preserve surrounding text and legacy formatting, including suffixes after `%next%`. Unknown external placeholders remain literal. Invalid numeric inline references fail rendering before initial actions. The original renderer discarded suffixes and processed only the first marker; those losses are not reproduced.
- Mark generated control components as indivisible typewriter units, including non-clickable movement-selection labels. This uses explicit component identity within the render frame, without attaching hidden metadata to displayed text. Option redraw never restarts typing or reruns dialogue actions. Holograms use resolved option labels independently of the chat animation.
- Rebind to the current player even when termination follows respawn before the next session tick. Interruption actions operate on the replacement player, and the prior owned ActionBar is cleared without another status refresh.
- Share existing built-in textual placeholder resolution with conditions. Runtime coverage exposed that `%player_level%` was already supported in dialogue text but previously rejected in conditions. Unsupported provider placeholders still have no result and cannot satisfy a condition.

The literal `json:` component path retains the original direct-component behavior; `%option_N%` expansion applies to legacy text lines. Native JSON click commands can use the restored command alias. Full JSON authoring/parser parity and the original Bukkit event API are not claimed by this change.

## Verification

The isolated dialogue-display fixture passes **127 checks**, retaining its prior 84. New cases cover filtered numbering, multiple markers/suffixes, hidden rows, full hologram labels, premature/stale/double clicks through the actual command dispatcher, changed/revoked requirements, unknown placeholder rejection, original command alias, typed input, atomic selection labels, movement/sneak selection, no repeated writer, cross-node/tenth-option routes, random/skipped terminal lines, malformed indices, interruption action counts, current-node routing, preflight failure, reentrant commands, teleportation, range exit, real respawn, logout, reload and shutdown.

Five additional unit cases cover node parsing/defaults, style/suffix preservation, unknown placeholders, non-clickable typewriter atoms and command restrictions. Final NeoForge 21.1.248 validation passes **60 dialogue unit tests, 107 NPC assertions, 223 runtime probes and 166 ordinary tests**, in addition to the 127 display checks. Unit failures/errors/skips are zero. The normal build succeeds and release jars contain no opt-in probes. Unrelated dedicated entity/pickup/movement/text/provider/removal suites were not rerun for this dialogue change. Exact gates, totals, source-jar identity and release hashes are recorded in `artifacts/dialogue-inline-validation-summary.json`; fixture commands are in [the audit README](../tools/dialogue-display-runtime-audit/README.md).

Physical-client appearance, complete modpack acceptance, incoming-chat restoration, dialogue authoring/actions, quests/items, group changes, the remaining Citizens APIs/parameters and Sentinel behavior remain open in [the current parity report](npc-parity-status-2026-09-13.md). Camera playback remains CmdCam-owned and economic APIs remain Yuuniverse Economy-owned. No production files were changed or deployed; the full goal remains active.
