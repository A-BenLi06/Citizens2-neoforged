# Dialogue typewriter and ActionBar status

Implementation follow-up for 2026-09-18 (UTC+08:00), after `4e3b299`.

## Reference and behavior

Inspected the installed Interactions 2.14.1 jar, its bundled defaults and the original server's config/messages. `WriteDialogueTask`, `DialogueTask`, `ActionBarTask`, `PlayerConversation` and `ConversationManager` establish the following behavior:

- `write_dialogues.enabled` defaults to false, `mode` to WORD and `delay` to 2 server ticks. CHARACTER and WORD operate on successive rows, redraw preceding rows and clear the visible chat with nineteen empty messages before each frame. `use_empty_spaces` adds the initial separator separately.
- Dialogue actions and the duration timer start when the line starts; they do not wait for animation completion. A manual-duration line remains active after all text is written. Skip ends the dialogue directly rather than revealing the rest as a separate first click.
- `action_bar` defaults to false. It refreshes the conversation status every twenty ticks. Its two message keys are `actionBarTitleConversation` and `actionBarTitleSelectOption`, with `%name%` substitution and removal of `{centered}` from the name.
- The installed ActionBar task mistakenly uses the selection title in both branches. The port uses the speaking and selection messages for their respective phases. Bundled English defaults remain translatable fallbacks; the active server's custom language is read from messages rather than embedded in code.

The inspected active server has both optional features disabled. This change supplies their configuration and runtime behavior without changing that server configuration.

## Implementation

`WriteDialogueSettings` validates the legacy section, including positive integral tick delays. Malformed settings/messages retain the previous snapshot. Existing `DialogueSettings` and `DialogueMessages` constructors remain available.

Each `Session` owns its `DialogueWriter` and `DialogueActionBar`. Animation uses the existing session tick, with no independent tasks or asynchronous player access. Initial and completion actions run once on their existing lifecycle boundaries. Finishing/skipping a line, replacing its node or ending the session discards the writer. Selection redraw displays the complete line and never starts another writer.

The writer animates parsed components rather than cutting raw JSON or formatting sequences. CHARACTER advances by Unicode code point, and WORD retains leading, repeated and trailing whitespace across style boundaries. Completed and empty rows remain in subsequent frames. Click/hover/style data survives partial text. Next controls appear whole. Translation, keybind, score and NBT component contents also appear whole, preserving native client interpretation rather than flattening them into the server's language. This is not grapheme-cluster animation: combining sequences can take multiple CHARACTER steps.

ActionBar uses private vanilla packets, refreshes once per second and updates immediately when the phase or title changes. Disabling it or ending the session clears its prior status once. Respawn rebinds the writer/status to the replacement player without resetting text progress. The session validates world/range before sending another frame; an invalid replacement still clears the prior owned status.

BossBars and holograms retain their independent behavior. Full hologram text remains visible while chat is animated. The original typewriter entry returns before updating its hologram, and a timed final writer can continue clearing the chat after options appear; those task-lifecycle defects are not reproduced. The port cancels text updates at completion and keeps the configured hologram available. Camera playback remains CmdCam-owned and economic APIs remain Yuuniverse Economy-owned.

## Verification

The extended isolated display fixture passes **84 checks**, including its prior 45 checks. It now captures actual system-chat packets rather than overriding player delivery. Tests cover both animation modes, Unicode, spacing, private recipients, independent holograms, single execution of rewards, manual and timed completion, sequential/node replacement, selection redraw, status cadence and disable/re-enable, simultaneous sessions, failed rendering/actions, range/dimension exit, actual player respawn, logout, reload and shutdown. Seven new unit tests cover settings/messages, exact tick delays, component/style preservation, native translation/control atoms and empty/multiline text; the full dialogue unit suite passes **55 tests**.

Final validation on NeoForge 21.1.248 also passes **107 NPC assertions, 223 runtime probes and 166 ordinary tests**. Unit failures/errors/skips are zero. The normal build succeeds and all release jars exclude the opt-in probes. Unrelated entity, pickup, movement, text-editor and provider-specific suites were not rerun for this dialogue change; their previous results remain historical evidence. Commands and fixture boundaries are in [the audit README](../tools/dialogue-display-runtime-audit/README.md). Completion markers, log paths and jar SHA-256 hashes are recorded in `artifacts/dialogue-writing-validation-summary.json`.

Packet/state checks do not prove physical-client typography, scrolling or full modpack acceptance. Inline options, incoming-chat restoration, other dialogue authoring/actions/configuration and the remaining Citizens/Sentinel/provider gaps stay open in [the current parity report](npc-parity-status-2026-09-13.md). The full goal remains active. No production deployment was performed.
