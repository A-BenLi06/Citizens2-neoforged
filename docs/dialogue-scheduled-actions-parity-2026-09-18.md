# Scheduled dialogue actions and ActionBar ownership

Updated: 2026-09-18 01:25:45 +08:00 (UTC+08:00). Implementation base: `2860710`. Validation target: Minecraft 1.21.1 / NeoForge 21.1.248, JDK 21.

## Reference behavior

The actual Interactions 2.14.1 `ActionUtils` dispatch and its two wait runnables schedule the rest of an action list from the following index. `wait` multiplies its integer argument by 20 using long arithmetic; `wait_ticks` uses raw ticks. The delayed runnable carries the action list, player and conversation entity, with no active-session check. Placeholders are expanded again as each action executes. Dialogue timers and separate initial/completion/option/interrupt action lists do not wait for these scheduled lists.

The bundled `ActionBarAPI` sends the message immediately, clears it at `duration + 1` ticks for nonnegative duration, and refreshes it at positive offsets `duration - 40`, `duration - 80`, etc. For example, duration 90 sends at offsets 0, 10 and 50, then clears at 91. Duration 40 only sends at 0 and clears at 41. Negative duration is a single send with normal client expiration. Its refresh runnable reuses the already-expanded text.

Reference evidence remains in `artifacts/legacy-action-utils.txt`, `legacy-action-schedules.txt`, and `legacy-actionbar-refresh.txt`. The original plugin SHA-256 remains `ce4824a2bb4317eb9acd40cd4388eebedfab521d96310bf74f2d26f0ac85293e`.

## Implementation

`Actions` now owns pending batches and advances them once per actual server tick through `InteractionsMod`. A batch executes its immediate prefix and returns an `ActionExecution` receipt. That receipt distinguishes PENDING, SUCCEEDED, FAILED and CANCELLED; completion observers run once, including observers attached after termination. An observer exception is logged without suppressing other observers or cancellation cleanup. The compatibility `runAll` method reports acceptance, while sessions use `executeAll` to observe actual completion. The obsolete synchronous `ActionBatch` helper has been removed.

Waits retain an immutable list snapshot, continue at the next action index, and use long deadlines. No thread sleeps or per-tick command polling are involved. Initial preflight still checks the complete batch and combined item costs before its prefix executes. Each resumed tail is checked again against current inventory, permissions and providers before mutation; placeholders also resolve again against the current player. As with the existing preflight model, initial validation uses the submission-time player state; it does not simulate future commands or future inventory gains. Earlier successful side effects are not rolled back if a later resumed segment fails.

Player identity includes the original connection. A delayed batch follows the actual replacement ServerPlayer after respawn on that connection. It cannot attach to a later login with the same UUID. Normal visual completion and ordinary interruption leave accepted batches running while that player remains connected, matching the independent original schedules. Logout, reload and shutdown cancel pending work. Reload also cancels the currently executing batch if its own command triggered the reload, so its remaining old actions cannot run afterward. Immediate interruption actions run after releasing session controls; deferred interruption work is cancelled at these lifecycle boundaries.

Sessions keep the original dialogue pacing and option routing while actions wait. A saved line is recorded only after its initial action batch succeeds. A normally ended conversation is recorded only after all of its tracked initial, completion and option batches succeed. A failure can interrupt a still-active originating session; a failure after visual completion prevents the saved completion without affecting a newer session. Earlier successfully saved lines remain saved. Ordinary interruption never creates a conversation-completed record.

`actionbar: text;duration` uses a shared private channel with the existing conversation status. For timed actions, the latest action temporarily owns the channel. Status changes continue to update its underlying title, but cannot overwrite the action. Expiration restores the latest status, or clears the channel if the status has ended. An older action's clear cannot erase a newer action. Actual respawn resends the active text without resetting its deadline. Long durations retain one display record and its next refresh deadline, rather than allocating millions of scheduled tasks.

These ownership and lifecycle rules intentionally correct stale scheduled clears, offline-player targeting and false completion. Zero waits yield until the next server tick, avoiding same-tick rescheduling loops. Wait arguments must be nonnegative integers; ActionBar duration accepts `-1` for a single send or a nonnegative integer. Malformed/overflowing values reject preflight instead of being coerced. Positive ActionBar refresh/clear offsets match the original API, including its nonuniform first refresh.

## Verification

The isolated loopback display fixture now continues across real server ticks after its existing synchronous checks. It exercises the production controller's tick, logout, reload and shutdown handlers, actual PlayerList respawn, a new login using the same UUID, actual commands and outgoing packets.

- **84 scheduled-action checks** cover immediate prefixes, raw/second/zero/terminal/maximum waits, immutable source lists, changed placeholders and inventory, initial and resumed failures, exact completion notifications, line/completion progress, independent option routing, normal/abnormal end, interruption batches, active/late failures, real respawn/relogin and lifecycle cancellation. ActionBar cases check duration -1/0/40/41/80/90, every expected send and every intervening tick, privacy, replacement, status ownership, phase restoration and exact expiry.
- **91 native-action checks** and **127 dialogue-display checks** remain in the same launch. The Gradle gate requires all three completion markers and rejects fixture failures.
- **61 dialogue unit cases** pass with no failures, errors or skips. The two old helper-only action tests were replaced with receipt-state tests; an observer-isolation case was added. Existing real-dispatch/payment failure coverage remains in the runtime suites.

Final general regression passes **107 NPC assertions** and **223 runtime probes**; **166 ordinary tests** also pass with no failures/errors/skips. The normal build succeeds. Release jars exclude the runtime probes and the removed ActionBatch class. Interactions SHA-256: `dd3015732e062602af23f967f21f42b8753d9782bf1161f5c73e187ab314b0c0`.

Release/log hashes and completion markers are recorded in `artifacts/scheduled-actions-validation-summary.json`. This is server-state/packet evidence, not physical-client typography or complete modpack acceptance. Unrelated dedicated entity/pickup/movement/text/provider/removal suites were not rerun for this batch.

## Remaining scope

The original action inventory is now **16/18**. `influence` still needs its entity-scoped persistence/API/commands/placeholders, and `send_to_server` needs actual destination/provider integration. Action recognition is not a complete feature-parity percentage.

Unknown world names still incorrectly fall back to the overworld; explicit alias resolution remains required. Other dialogue authoring/configuration, incoming-chat restoration, quests/saved-item fidelity, GroupManager replacement capabilities, Citizens/API/command behavior, Sentinel and client/modpack/deployment acceptance remain open. CmdCam owns camera playback and Yuuniverse Economy owns economic APIs. No production files were modified or deployed; the full goal remains active.
