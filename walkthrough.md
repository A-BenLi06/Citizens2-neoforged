# Walkthrough

## 2026-09-08 21:09:20 +08:00 — NPC parity audit

- Inspected the current NeoForge working tree without incorporating the user's pre-existing uncommitted implementation changes into this commit.
- Fetched and pinned CitizensDev/Citizens2 upstream at `e2998fd5fb8486b2551c1bcd366e37c263a6b35f`; compared all main-module command declarations, including commands in versioned traits, and source counterparts.
- Read this project's Claude Code session history, memory, and port plan; cross-checked historical completion claims against the actual old plugin data and current code.
- Read the old Arclight plugin snapshot and deployed server data. Confirmed continuity of all 200 old NPC identities, deployed jar content equivalence, and partial companion-service coverage. Production files were not modified.
- Added `tools/audit_npc_parity.py`, opt-in dialogue acceptance probes, a Gradle init script for those probes, and `docs/npc-parity-audit-2026-09-08.md` with command/class/trait evidence tables.
- Reasoning: inventory and successful compilation do not establish execution semantics. Trace command consumers, transaction outcomes, saved-progress readers, configuration consumers, and actual service entry points. Treat stale comments/session summaries as hypotheses rather than proof.
- Verification: forced 112-test baseline passed. Four dialogue acceptance probes executed with three reproducible failures (two sound IDs and cooldown preservation). Two isolated test-server runs produced 103/106 and 105/106 passing assertions; knockback fixture failures remain unresolved. Isolated command lookup confirmed Bukkit-style `minecraft:give` and `minecraft:setblock` roots absent while their native counterparts exist.
- Result: audit acceptance fails. Implementation defects are documented, not represented as fixed. No production deployment or permission-system rewrite was performed. See the report for the full list of open gates and evidence limits.

## 2026-09-08 21:12:41 +08:00 — Preserve the existing implementation baseline

- Recorded the pre-existing NeoForge and Interactions implementation before parity repairs. No functional changes in this checkpoint; the untracked dialogue source set and its build wiring are now reviewable independently of subsequent fixes.
- Existing audit baseline: 112 ordinary tests pass, three dialogue acceptance failures and unresolved knockback fixture failures.

## 2026-09-09 10:25:19 +08:00 — Preserve legacy dialogue state and playback semantics

- Replaced lossy hand-written player YAML with a structured store that preserves unknown fields, dialogue keys and conversation-specific cooldown records. Save via a temporary file and replacement; retain failed writes for retry and refuse to overwrite unreadable records.
- Verified against the old Interactions bytecode that cooldown values are start timestamps, then restored duration-based checks scoped to each conversation and surviving restart.
- Connected saved-dialogue predicates to player progress, recheck option requirements at selection, shuffle random-dialogue candidates, and allow choices beyond nine.
- Resolve Bukkit sound constants against real sound registry paths so note_block underscores survive conversion.
- Validation: eight dialogue regression tests pass; ordinary test suite and build pass (112 tests). Production data and running server were not changed. This batch addresses state/playback defects; command/service parity and transaction failures remain open in the audit.
## 2026-09-09 10:27:06 +08:00 — Resolve legacy vanilla command roots

- Normalize a leading slash and Bukkit minecraft: command roots only when the namespaced root is absent and the native root exists. Apply this before dispatch and after alias rewriting; preserve all arguments, item/component namespaces, and registered mod commands.
- Fixed alias-result tokenization to recognize all whitespace, including tabs.
- Validation: ten dialogue regression tests pass and build succeeds. This repairs root lookup only; legacy NBT arguments, nested command migration, external command services and action failure propagation remain separate work.
## 2026-09-09 10:37:54 +08:00 — Preflight dialogue actions and propagate execution failures

- Prepare all actions before executing any. Reserve combined item costs against copied inventory, reject malformed amounts, and preflight delayed line actions before immediate payments. Missing saved items, unavailable economy/aliases, unknown commands, and syntax errors prevent payment from starting.
- Stop remaining actions and end the session on execution failure. Observe Minecraft command callbacks from server ticks; defer option actions out of the selecting command's execution queue. Failed sessions do not record completion.
- Honor explicitly named online saved-item/economy recipients. Added balance inquiries, exact configured currency scaling and economy exception propagation; unavailable currencies no longer invent a default identifier. Invocation keys are unique, with no automatic retry after uncertain outcomes.
- Added opt-in runtime probes using NeoForge FakePlayer, real inventories and the actual Minecraft dispatcher. Verified successful namespaced give and purchase, missing reward, insufficient items, runtime command failure, invalid command arguments, unavailable economy, delayed reward preflight, and deferred option execution.
- Validation: 15 dialogue regression tests pass; 10/10 dedicated-server action probes pass; 112 ordinary tests and the final build pass. Confirmed the release Interactions jar excludes ActionRuntimeAudit. Existing general NPC fixture remains 103/106, with three knockback failures.
- Limits: this is dependency preflight and failure propagation, not an atomic transaction spanning arbitrary commands, inventory and external services. Earlier successful side effects and separate lines are not rolled back. Offline recipients are explicitly rejected; successful economy integration still needs the real companion mod test. The audit report tracks remaining feature gates. No production deployment or live-server modification.
## 2026-09-09 20:43:27 +08:00 — Restore guard regeneration and make knockback acceptance reliable

- Verified Sentinel 2.9.0-b522 bytecode: regeneration adds one hit point after the elapsed timer exceeds healRate, only below maximum health; non-positive rates disable healing. Restored this persisted option with native tick timing and scaled-health support.
- Apply configured maximum/current guard health immediately at spawn, including when ScaledMaxHealthTrait was just attached. Apply invincible in both directions so ordinary guards do not retain Citizens' default protection.
- Diagnosed the knockback fixture without changing NPC motion logic. Entity sections became queryable later than ENTITY_TICKING chunks; the old damage/check schedules could run before either pig was visible. Once ready, the disabled actor stayed still and the control moved about 1.78 blocks. The original selector box still intersected that control, producing a false failure.
- Versioned the repaired fixture overlay: wait for both actors with a bounded timeout, assert damage success, compare centre-coordinate displacement, and retain existence assertions. Declared Gradle overlay inputs/outputs so edits actually reach the test world. Added required marker checks that fail the task on missing/failed runtime assertions.
- Verification: all 107 NPC scenario assertions, 10 dialogue action probes, and 10 new Sentinel probes pass on both NeoForge 21.1.233 and 21.1.248. On 21.1.248, all 15 dialogue regression tests and 112 ordinary tests pass; build succeeds. Verified that release jars exclude runtime probes.
- Production server/data were not modified. Full modpack/client behavior, remaining Sentinel features, permission/service bridges, and the other audit gates remain open. Read-only Paradigm inspection confirmed its versioned public API exposes permission queries but no primary-group mutation method; an additive alias would not reproduce GroupManager semantics.
## 2026-09-09 20:46:59 +08:00 — Restore native NPC creation and sneaking API behavior

- Matched upstream registry creation: attach ArmorStandTrait to armor stands and honor the configured DEFAULT_LOOK_CLOSE flag for new NPCs, preserving existing trait instances.
- Route NPC.setSneaking through SneakTrait so calls made while unspawned persist, apply at spawn, survive respawn, and can be disabled through the same API.
- Added eight real registry/entity probes using an anonymous in-memory registry. They verify both LookClose defaults, armor-stand attachment, unspawned sneak state, spawn application, serialized data, respawn, and disabling sneak.
- Validation on NeoForge 21.1.248: all required runtime markers pass (107 NPC scenarios, 10 action probes, 10 Sentinel probes, 8 native API probes); 112 ordinary tests and build pass. Release artifacts contain no runtime audit classes. Production remains unchanged; the full parity goal remains active.

## 2026-09-10 09:50:09 +08:00 — Abort rejected native shop payments and compensate completed transactions

- MoneyAction now reports rejected deposits and withdrawals as execution failures. Successful compensation restores both the player wallet and the finite shop balance; rejected compensation leaves accounting unchanged and is logged for reconciliation.
- Composed costs recheck each part immediately before execution, abort subsequent actions on failure, and undo completed actions in reverse order. Refund failures do not prevent other refunds, and preserve the original execution exception. Completed compensation is cleared to prevent duplicate refunds.
- Native shop purchases catch action failures, undo completed rewards and costs, and do not count failed purchases. NPC command charges stop on execution failure; already queued commands also stop after an earlier charge failure.
- Added five transaction regression tests and eight isolated dedicated-server shop probes using a controllable economy provider and synthetic reward action through the actual shop click path. They cover debit/credit accounting, both compensation directions, rejected payment, rejected refund, unavailable reward, and successful purchase.
- Validation on NeoForge 21.1.248: 117 ordinary tests pass; build succeeds; 107 NPC assertions and all 36 runtime probes pass. Release jars contain no runtime audit classes. Logs: artifacts/repair-shop-runtime248.log and artifacts/repair-shop-final248.log.
- Limits: external providers must accurately report whether mutations succeeded. Compensation is best effort, not a durable cross-service atomic transaction; failed refunds require reconciliation. The real Yuuniverse economy provider bridge, delayed command acceptance coverage, and remaining parity gates are still open. Production remains unchanged.

## 2026-09-10 09:55:07 +08:00 — Connect native Citizens transactions to the optional Yuuniverse economy API

- Added an optional reflection adapter for native shop and command MoneyAction operations. It uses the published EconomyApi interface and configured default currency ID, symbol, and minor-unit scale; it requires neither the dialogue mod nor an economy compile dependency.
- Install at ServerStarted, after the economy publishes its service during ServerStarting. Preserve an existing custom provider and only unregister the adapter instance owned by Citizens at shutdown.
- Convert amounts exactly using BigDecimal; reject negative, non-finite, overflowing, or excess-precision mutations. Propagate rejected ledger operations as false so the transaction repairs abort rewards. Give each invocation a unique operation key and never retry uncertain writes. Balance lookup failure is unavailable (-1), not a fabricated balance.
- Verified public method signatures against the production Economy 0.4.1 jar without modifying production. Added three regression tests covering configured precision, reflected credit/debit/balance calls, unique keys, formatting, rejected writes, zero-value operations, and unavailable balance lookup.
- Validation on NeoForge 21.1.248: 120 ordinary tests pass and final build succeeds. The isolated server without the optional economy mod passes all 107 NPC assertions and 36 runtime probes. Release jars exclude audit probes.
- Remaining acceptance: run with the real economy mod and isolated ledger to verify service lifecycle, frozen/missing accounts, successful payments, refunds, and persistence. This change does not establish complete integration or full Citizens/plugin parity. Production remains unchanged.

## 2026-09-10 09:59:05 +08:00 — Preserve decimal NPC prices in batch purchases and refunds

- Replaced binary floating-point multiplication/division in MoneyAction with decimal arithmetic. Prices such as 0.1 repeated three times now produce 0.3, and a balance of 0.3 can afford three purchases at 0.1.
- Apply decimal addition/subtraction to finite shop balances so compensation restores the original decimal value. Validate that the resulting amount is representable by the double-based EconomyProvider before calling the external mutation; do not round large unrepresentable balances or mutate the wallet before detecting accounting overflow.
- Reject negative or non-finite persisted prices and negative repeat counts; reject non-finite editor input. Keep zero-cost actions valid and unavailable economy balances unaffordable.
- Added four regression tests for repeated decimal prices, affordability boundaries, compensation arithmetic, numeric range failures and invalid action configuration.
- Validation on NeoForge 21.1.248: 124 tests pass, final build succeeds, and the isolated server passes all 107 NPC assertions and 36 runtime probes. Release jars contain no runtime probes. Logs: artifacts/repair-money-final248.log and artifacts/repair-money-arithmetic-runtime248.log.
- Real-economy ledger integration acceptance and the other full parity gates remain open. Production was not modified.

## 2026-09-10 10:03:13 +08:00 — Verify native NPC money against the real Economy ledger and restart

- Added a separate opt-in server fixture using the actual production-version Economy 0.4.1 jar, a fresh audit currency and SQLite ledger, and loopback port 25579. Production files were read only to copy the jar; the production world/database were never used for writes.
- Ten first-pass checks verify native provider installation, initial balance, exact batch affordability/debit, refund of wallet and finite till, insufficient funds, excess precision, frozen accounts, missing accounts, and the saved final balance. All pass on NeoForge 21.1.248.
- A second server process reloads the same isolated ledger and verifies the provider is installed and the final 9.7 balance survived shutdown/restart. Both runtime Gradle tasks require explicit success markers and fail on probe errors.
- Added a fixture preparation script and reproducible commands. The fixture account is intentionally reset only by the first audit pass; the verification pass reads it. Existing fixture worlds/configs are preserved by preparation.
- Final normal build and 124 regression tests pass; release jars exclude runtime probes. Recorded the narrowed real-mod acceptance evidence in the parity report without marking the overall audit complete.
- No additional product-code defect was reproduced in these ledger paths. Dialogue economy success, full native command/UI entry points, all remaining plugin parity gates, and full modpack/client acceptance remain outstanding.

## 2026-09-10 16:42:34 +08:00 — Restore configured dialogue radius and entry checks

- Inspected original Interactions 2.14.1 bytecode. Proximity entry scans run every 20 ticks; positive start radius enables entry, zero disables it. The entry marker is set before attempting a conversation, avoiding repeats while the player remains in the same conversation region. End radius zero disables distance termination; nonzero boundaries have no extra half-block allowance.
- Added proximity scanning over spawned Citizens NPCs, matching conversations by ID or name, selecting the nearest eligible NPC in the same level, and clearing entry state on region exit/logout/reload/stop. Click and proximity entry share existing cooldown checks plus legacy permission and air-start checks. The permission node is interactions.start.<conversation id>; the old plugin tests solid ground one block below the player's position when air starts are disabled.
- Corrected the startRadius unit comment, removed its incorrect claim that all migrated files use zero, and fixed session termination at zero/exact end radius.
- Verification on NeoForge 21.1.248: 16 dialogue tests, 124 ordinary tests and normal build pass. All 107 NPC assertions and 40 runtime probes pass, including four new actual-session tests for exact boundary, just outside boundary, unlimited distance and removed NPC. Release artifacts exclude runtime probes.
- Acceptance still needed: automatic entry/re-entry with real connected players, permission/air checks across modded blocks, movement locking, global dialogue restrictions and other original parity gates. No production changes. The report explicitly separates these remaining flows from the verified session-distance behavior.

## 2026-09-10 16:48:17 +08:00 — Exercise proximity entry and re-entry through the dialogue controller

- Extracted the existing per-player proximity scan into a package-visible method. The live server continues calling the same logic every 20 ticks with its Citizens registry; no behavior change is intended by this extraction.
- Added an opt-in controller-level audit using a separately instantiated/unregistered controller, an anonymous NPC registry, a real spawned NPC, a temporary legacy conversation file and NeoForge FakePlayer. No production sessions/configuration are used.
- Eight checks pass: named conversation loading, outside-radius rejection, boundary entry, active-session preservation, no restart while remaining inside, restart after exit/re-entry, missing-permission rejection, and disabled-air-start rejection. The first fixture attempt used an incorrect plural YAML key; corrected it to the old singular conversation key and reran successfully. The failed run is not acceptance evidence.
- Validation on NeoForge 21.1.248: all 107 NPC assertions and 48 runtime probes pass; 124 ordinary tests and normal build pass; release jars contain no runtime probe classes. Logs: artifacts/repair-proximity-runtime248-final.log and artifacts/repair-proximity-final248.log.
- Updated the audit to distinguish these verified controller paths from connected-client input/network behavior, granted-permission/modded-ground variants, movement locking and other remaining parity gaps. No additional product defect was reproduced in the tested entry paths. Production remains unchanged.

## 2026-09-11 00:38:45 +08:00 — Enforce dialogue horizontal movement restrictions in the packet handler

- Implemented block_movement for unmounted players with a session-owned restriction map and a separate Interactions mixin. The injection runs on the server thread after vanilla validates movement values and handles pending teleport acknowledgements. Horizontal displacement is rejected with the normal position confirmation; look-only and vertical-only packets continue through vanilla processing.
- Session creation acquires the restriction only when configured; all end paths release it. Identity-based removal prevents a stale session from releasing a replacement session's lock. The implementation does not rewrite movement attributes or reposition players every tick.
- Eight real packet-handler probes pass: lock acquisition, horizontal rejection, rotation on rejection, vertical movement, rotation-only packets, replacement ownership, release and normal movement afterward. The first fixture omitted player chunk tracking; corrected player registration/removal and reran. That initial failure is excluded from acceptance.
- Validation on NeoForge 21.1.248: all 107 NPC assertions and 56 runtime probes pass; 124 ordinary tests and normal build pass. Release Interactions jar contains the movement hook/config and no runtime probes. Logs: artifacts/repair-movement-runtime248-final.log and artifacts/repair-movement-final248.log.
- Remaining scope: vehicle movement, camera and movement-based option selection, actual connected-client correction visuals and full modpack acceptance. The audit explicitly leaves these open; this does not establish complete movement/cutscene or overall Citizens/plugin parity. Production remains unchanged.

## 2026-09-11 01:13:56 +08:00 — Apply dialogue movement restrictions to controlling vehicle packets

- Added the vehicle packet counterpart to the existing on-foot restriction. It runs after vanilla validates packet values, pending teleports, controlling passenger identity and the tracked vehicle. Blocked horizontal displacement sends the normal vehicle position correction while preserving rotation; pure vertical movement continues through vanilla handling.
- Extended the movement acceptance probe from eight to thirteen checks using a registered ServerPlayer and actual boat. Tests establish control, reject horizontal movement, retain rotation, allow vertical movement, and restore vehicle movement after the session ends.
- Corrected two fixture assumptions discovered by execution: FakePlayer intentionally refuses riding, and mounting ServerPlayer requires acknowledging the vanilla teleport before sending movement. Prior failed runs are excluded; the successful run proves the new hook executes via rotation and post-release movement checks.
- Validation on NeoForge 21.1.248: all 107 NPC assertions and 61 runtime probes pass; 124 ordinary tests and final build pass. Release jars exclude runtime probes. Logs: artifacts/repair-vehicle-acceptance248.log and artifacts/repair-vehicle-final248.log.
- Still open: noncontrolling passengers, modded vehicle behavior, connected-client visuals and camera/selection controls, along with other full parity gates. Production was not modified.

## 2026-09-11 01:20:59 +08:00 — Restore legacy dialogue chat and mob-target settings

- Read allow_chat_while_in_conversation and allow_mob_damage from the existing Interactions config.yml at startup and reload. Missing keys use legacy false defaults; malformed reloads retain the previous complete snapshot. Unknown settings are preserved because loading never rewrites the file.
- Ordinary chat follows the configured policy while valid dialogue answers remain private. With mob targeting disabled, starting a conversation clears nearby mobs targeting that player and the NeoForge target-change event blocks new targets until the session finishes. This is target suppression, not damage immunity, matching the inspected legacy behavior.
- Added three settings tests (19 dialogue audit tests pass) and four controller/event probes covering chat denial/allowance and actual Mob.setTarget denial/allowance. All 107 NPC assertions and 65 runtime probes pass on NeoForge 21.1.248. Evidence: artifacts/repair-global-settings-tests248.log and artifacts/repair-global-settings-runtime248.log.
- Initial nearby-target clearing, connected-client chat display, command/inventory restrictions, camera controls and complete modpack parity still need further acceptance. No production files were changed.
- Final validation: 124 ordinary tests and normal build pass; all release jars exclude runtime audit classes. Evidence: artifacts/repair-global-settings-final248.log.

## 2026-09-11 01:26:32 +08:00 — Restore dialogue command restrictions without blocking rewards

- Implemented allow_commands_while_in_conversation and commands_whitelist in the existing settings loader. Preserved the legacy lowercased-input prefix matching, including prefix matches beyond a command root; invalid whitelist values retain the previous snapshot. The current interactions choose transport remains available for option clicks.
- Added session-owned command restrictions with identity-based cleanup. The controller supplies its current settings so policy changes do not require capturing stale settings in sessions.
- Hooked the two connection command execution paths immediately before command dispatch, after vanilla signature validation/chain processing. Internal server commands, including Actions.player_command_as_op, never enter these hooks and continue executing. No global command event cancellation is used.
- Added two configuration tests and ten isolated runtime probes: unrestricted baseline, blocked unsigned/signed execution paths, internal player-source execution, whitelist unsigned/signed paths, global allowance, option transport allowance, replacement ownership and release. Runtime acceptance passed all 107 NPC assertions and 75 probes on NeoForge 21.1.248. Dialogue audit suite: 21 tests passed. Logs: artifacts/repair-command-runtime248.log and artifacts/repair-command-tests248.log.
- Runtime probes invoke the real connection execution methods on the server thread; their signed packets have no signable arguments. Connected-client cryptographic signature chains, UI interaction, inventory restrictions, legacy skip-dialogue controls and complete modpack coverage remain unverified/open. Production was not changed.
- Final validation: 124 ordinary tests and normal build passed; packaged jars contain no runtime audit classes. Evidence: artifacts/repair-command-final248.log.

## 2026-09-11 01:34:38 +08:00 — Restore next-dialogue buttons and NPC click skipping

- Inspected Interactions 2.14.1 bytecode: the skip command requires a current line containing %next%; NPC-click skipping is separately opt-in and also applies to ordinary lines. Both complete the current line through last_actions and refuse to bypass option selection.
- Added %next% rendering and the interactions skipdialogue command, with the same prefix-before-marker rendering as the legacy sender. Custom nextDialogueText/nextDialogueHover are loaded from messages.yml at startup/reload; absent messages use translatable fallbacks and malformed reloads retain the previous snapshot. Unknown message keys are never rewritten.
- Added skip_dialogue_on_npc_click handling through the existing NPC right-click controller. Skip requests are deferred to the session tick, preserving the command execution queue boundary and accepting only one pending completion. End paths clear the request.
- Added three dialogue tests and fourteen runtime checks: next button content/command/hover, command dispatch, initial/last reward counts, repeated clicks, unavailable/currently finished lines, option gating, opt-in ordinary-line skipping, and two actual NPC click-controller checks. Final runtime acceptance passed 107 NPC assertions and 89 runtime probes on NeoForge 21.1.248. Dialogue audit tests pass. Logs: artifacts/repair-skip-runtime248-final.log and artifacts/repair-skip-tests248.log.
- The first runtime run finished its original checks but failed the completion manifest after two additional NPC-click assertions were added while it was running. It is excluded from final acceptance; the complete rebuilt fixture passed on rerun.
- The inspected old conversation directory currently has no %next% files and NPC-click skipping is false; this restores a legacy capability without enabling it in production. Connected-client button rendering/input, animated text behavior, inventory restrictions and other full parity gates remain open. No production changes.
- Final validation: 124 ordinary tests and normal build pass; release jars exclude runtime audit classes. Evidence: artifacts/repair-skip-final248.log.

## 2026-09-11 12:22:01 +08:00 — Restore dialogue routing and manual-duration semantics

- Found an active migration defect in legacy story files such as Prelina apartment day one: line-level start_conversation was ignored. The legacy scheduler completes the terminal line's last_actions before an automatic jump; start_options instead borrows another node's choices and takes precedence over a jump. Added these fields to the parser and implemented their separate routing paths without playing the borrowed node's dialogue.
- Corrected conditional_dialogue from replacement text to ordered conditional redirects. Legacy bytecode checks nonempty requirements before showing the original line or executing its actions; the first matching redirect wins. Missing targets stop before the source actions. Conditional and automatic cycles advance through server ticks instead of recursive calls.
- Added terminal-route validation before line actions, retained last-action failure propagation, and reset routing state when changing nodes. Earlier sequential lines do not apply their terminal-route fields, matching the inspected legacy ordering.
- Corrected time: -1 to wait for manual advancement instead of completing after one tick. Other timed lines keep the existing scheduler behavior.
- Validation: 25 dialogue audit tests passed; added 18 routing runtime checks plus a manual-wait check. Final isolated acceptance on NeoForge 21.1.248 passed all 107 NPC assertions and 108 runtime probes. Evidence: artifacts/repair-routing-runtime248-final.log, artifacts/repair-routing-tests248.log and artifacts/repair-routing-dialogue-tests.xml.
- The first runtime run failed the pre-existing pathfind-minecraft-detour assertion (106/107) while its initial routing checks passed. The final rerun passed that unchanged pathfinding check and the expanded routing suite. The earlier failure remains recorded and its cause is not established; it is not counted as successful acceptance.
- Actual connected-client story playthroughs, complete reward/service migration, inventory restrictions, option presentation and full modpack coverage remain open. No production data was modified.
- Final validation: 124 ordinary tests and normal build passed; release jars contain no runtime audit classes. Evidence: artifacts/repair-routing-final248.log.

## 2026-09-11 12:25:05 +08:00 — Validate option destinations before executing actions

- Found that a selected option ran its actions before resolving start_conversation. A misspelled destination therefore consumed payment or issued rewards and then recorded a successful completion because missing targets and intentional end options shared the same null result.
- Resolve an explicitly configured option destination before any selected-option actions execute. A missing destination logs the configuration error and ends unsuccessfully. An omitted destination retains its intentional end behavior; valid destinations still execute actions and enter the next node.
- Added three real-inventory/progress runtime assertions: missing targets preserve both payment and rewards, missing targets do not mark the source completed, and valid targets still consume payment, grant the reward and enter the target node.
- All 107 NPC assertions and 111 runtime probes passed on NeoForge 21.1.248, including the previously intermittent pathfind-minecraft-detour check. Evidence: artifacts/repair-option-target-runtime248.log. This does not establish why that earlier failure occurred.
- Remaining scope includes broader dialogue UI/global restrictions, reward/service migration and real-client full-modpack acceptance. No production files changed.
- Final validation: 124 ordinary tests and normal build pass; release jars exclude runtime audit classes. Evidence: artifacts/repair-option-target-final248.log.

## 2026-09-11 17:16:33 +08:00 — Enforce configured dialogue inventory restrictions before item removal

- Implemented allow_inventory_interact_while_in_conversation; explicit false restricts player input, while the bundled legacy default true keeps existing unrestricted behavior. Session ownership prevents an older session from releasing its replacement, and current settings are queried dynamically.
- Added connection hooks for container clicks, both held-item drop variants, and creative slot/drop packets. Container clicks are stopped after menu/slot validation and before remote updates are suppressed; drops are stopped before inventory removal. Rejected operations resend authoritative inventory state. Internal inventory changes remain available to rewards.
- Added NeoForge main-hand right-click-item, right-click-block and left-click-block cancellation while configured restrictions apply. Preserved the legacy offhand exclusion.
- Avoided canceling ItemTossEvent after removal: inspected NeoForge CommonHooks shows that cancellation does not restore the removed item. Runtime checks prove the input hooks retain the stack and do not create a dropped entity.
- Validation: 26 dialogue audit tests pass; final isolated NeoForge 21.1.248 run passes all 107 NPC assertions and 127 runtime probes. Sixteen new probes cover ordinary/quick-move clicks, single/stack drops, creative replacement/outside-drop, three interaction events, offhand behavior, internal inventory mutation, replacement ownership, live setting changes and restored pickup/drop after session end. Logs: artifacts/repair-inventory-runtime248-final.log, artifacts/repair-inventory-tests248.log and artifacts/repair-inventory-dialogue-tests.xml.
- First runtime attempt failed due to an incorrect creative-packet accessor in the mixin target (getItem instead of itemStack). Corrected against the mapped 1.21.1 API and reran successfully; the failed run is excluded from acceptance.
- Still open: connected-client inventory prediction/display, special modded containers, physical block interactions such as pressure plates/trampling, and legacy denial-message customization. This is not full global-restriction or modpack parity. Production was not modified.
- Final validation: 124 ordinary tests and normal build pass; all release jars exclude runtime audit classes. Evidence: artifacts/repair-inventory-final248.log.

## 2026-09-11 17:25:08 +08:00 — Restore configurable option presentation and clickable behavior

- Read legacy optionsFormat, clickableOptionHover and optionsMainFormat from messages.yml, preserving unknown keys and retaining the previous snapshot after malformed reloads. Option text/hover substitute the legacy number tokens; text retains legacy colors and supported player placeholders.
- Render the configured outer layout, expanding lines containing %options% into the filtered option list as the old sender does. Honor clickable_options and option-stage use_empty_spaces. Disabled clicks omit command/hover events while typed choices remain available.
- Removed hardcoded Chinese option instructions and hover text. Missing message overrides use translatable English labels/prompts; configured layouts are not appended with an extra default prompt.
- Corrected typed option text matching to expand player placeholders consistently with the displayed option body. Hidden options retain consecutive visible indices, and click commands select the corresponding visible reward.
- Validation: 29 dialogue audit tests passed. Final isolated NeoForge 21.1.248 acceptance passed 107 NPC assertions and 137 runtime probes. Ten new checks cover layout, filtering/indices, hover substitution, color, actual reward selection, disabled clicks, typed selection, fallback prompts/spacing and expanded-name matching. Logs: artifacts/repair-option-display-runtime248-final.log, artifacts/repair-option-display-tests248.log and artifacts/repair-option-display-dialogue-tests.xml.
- Still open: selectable movement/scroll controls and selected-row formatting, inline options, global message prefixes/name formatting, other message overrides, actual connected-client rendering and full modpack parity. No production changes.
- Final validation: 124 ordinary tests and normal build pass; release jars exclude runtime audit classes. Evidence: artifacts/repair-option-display-final248.log.

## 2026-09-11 17:34:17 +08:00 — Restore selectable option movement, scrolling and sneak confirmation

- Added selectable_options, selectable_options_mode and selectable_options_restart_on_overflow with validated MOVE/SCROLL modes. Preserved the original 200 ms repeat delay, directional thresholds, wrap/clamp behavior and requirement for dialogue movement locking.
- Integrated unmounted directional selection with the existing validated movement-packet hook before position correction. Scroll mode intercepts held-slot input on the server thread, preserves the held slot and resynchronizes it. Sneak input confirms the highlighted option through the existing deferred-action path.
- Added selected/normal row message overrides and translatable fallbacks. Extracted line rendering from line actions so legacy-style selection redraws can repeat text without recharging or issuing rewards. Selection rows use their own presentation rather than clickable-option formatting.
- Added fourteen real connection/session checks covering initial formatting, forward/backward/sideways input, stationary coordinates, repeat delay including the exact 200 ms boundary, scroll wrap/clamp and held-slot preservation, disabled/unlocked behavior, deferred sneak confirmation and single reward execution, and redraw action counts.
- Validation on NeoForge 21.1.248: 31 dialogue tests, all 107 NPC assertions and 151 runtime probes pass. Evidence: artifacts/repair-selection-runtime248.log, artifacts/repair-selection-tests248.log and artifacts/repair-selection-dialogue-tests.xml.
- Still open: actual connected-client selection visuals/input, passenger input and camera/fake-mount behavior, inline options, global message prefix/name formatting and full modpack parity. No production changes.
- Final validation: 124 ordinary tests and normal build pass; all release jars exclude runtime audit classes. Evidence: artifacts/repair-selection-final248.log.

## 2026-09-12 12:09:08 +08:00 — Restore legacy NPC conversation click modes

- Restored conversation_start_click_type from config.yml: RIGHT_CLICK permits standing right-clicks, SHIFT_RIGHT_CLICK permits sneaking right-clicks, and ALL_RIGHT_CLICK permits both. Verified the truth table against the original Interactions 2.14.1 NPCManager bytecode (artifacts/legacy-npc-entry.txt). The gate precedes both conversation start and NPC-click skipping; proximity entry remains independent.
- Invalid modes retain the previous complete settings snapshot. Existing constructor overloads preserve the legacy RIGHT_CLICK default. Configuration is read without rewriting unknown keys.
- Validation: 33 dialogue audit tests pass (artifacts/repair-click-mode-dialogue-tests.xml). The final isolated NeoForge 21.1.248 runtime passes 107 NPC assertions and 158 probes, including six mode/sneaking combinations and independent proximity entry (artifacts/repair-click-mode-runtime248-diagnostic.log).
- The first runtime attempt failed ten existing assertions: bat-awake, hologram-line-rendered, disguise-cosmetic-spawned, disguise-keeps-real-entity, fish-hook-cast, template-yaml-replace-changed-type, packet-control-still-in-world, citizens-save-npc-alive, rotate-smooth-reached-target and rotate-control-untouched (artifacts/repair-click-mode-runtime248.log). Its cause remains unconfirmed. Added opt-in entity visibility snapshots at ticks 79, 120 and 200; the rerun shows the selected actors visible at their expected locations and passes the unchanged assertions. This is evidence of a successful rerun, not proof that the intermittent failure is fixed. The diagnostics do not change assertion timing or production behavior.
- Complete original-plugin/modpack parity, fixture reliability and real connected-client acceptance remain open. Production was not modified.
- Final validation: 124 ordinary tests and normal build pass (artifacts/repair-click-mode-final248.log). All three release/source jars exclude RuntimeAudit classes.

## 2026-09-12 12:15:14 +08:00 — Restore standalone dialogue speaker headings and line spacing

- The original Interactions 2.14.1 ConversationManager.sendDialogueMessage calls sendName once before iterating the text list. Its sendName uses messages.yml nameFormat and show_name. Replaced the port's repeated inline name-plus-colon concatenation with one configured heading before the body. Empty formats suppress the heading; unnamed conversations remain without headings. Missing overrides use an English translatable fallback.
- Applied use_empty_spaces before dialogue rendering, complementing the existing option-stage behavior. Selection redraws use this same rendering path without replaying line actions. Name formatting and body spacing can reload through the existing settings/message snapshots; invalid nameFormat values retain the previous snapshot.
- Verified the old MessagesManager bytecode (artifacts/legacy-messages-manager.txt): the boolean passed by ordinary dialogue/name rendering is false, so the configurable global prefix must not be added to this path. Global command/status prefixes remain separate outstanding work.
- Validation so far: 34 dialogue audit tests pass (artifacts/repair-speaker-dialogue-tests.xml); six new runtime checks cover a single heading before a multiline body, color, show_name false, empty format, configured spacing and the next button. Full runtime and release build results are recorded below.
- This does not establish animated text, inline options, hologram/camera dialogues, complete message coverage or connected-client/modpack parity. Production was not modified.
- Full isolated NeoForge 21.1.248 runtime passes 107 NPC assertions and 164 probes, including the existing selection redraw reward-count check. Evidence: artifacts/repair-speaker-runtime248.log. No runtime audit failure occurred in this run; the earlier intermittent entity visibility failure remains unresolved.
- Final validation: 124 ordinary tests and normal build pass (artifacts/repair-speaker-final248.log); all three release/source jars exclude RuntimeAudit classes.

## 2026-09-12 17:32:52 +08:00 — Restore JSON dialogue components before executing line actions

- The original Interactions 2.14.1 ConversationManager recognizes a leading json: marker and sends parsed chat components (artifacts/legacy-conversationmanager.txt). The port previously rendered this serialized content as literal legacy text. Added Minecraft 1.21.1 component parsing with server registry access, preserving component structure, styles and click/hover events.
- Expand placeholders in parsed JSON string values rather than concatenating them into serialized JSON. Only the leading marker is removed, preserving literal json: text inside a component.
- Prepare the entire dialogue text list before sending any of it or running line actions. A malformed component logs the conversation/node/line, ends the session unsuccessfully, releases input restrictions and prevents that line's payment/reward actions and partial display. Selection redraw uses the same rendering path and stops if rendering fails.
- Runtime probes cover text/player expansion, color, click, hover, invalid JSON preserving payment/reward inventory and absence of partial output. Unit coverage includes component arrays/nested styling, invalid/null components and legacy show_text hover value arrays. Validation results are recorded below.
- The inspected old active conversation files do not currently contain json: dialogue lines; this restores an original plugin capability for existing or new content. Item/entity hover payload conversion across Minecraft versions, inline options, animated/hologram dialogues, connected-client acceptance and complete modpack parity remain open. Production was not modified.

### 2026-09-12 17:40:44 +08:00 — Resolve entity-loading races and hanging NPC tick regression

- The first JSON runtime run (artifacts/repair-json-runtime248.log) recorded multiple NPC failures. New snapshots establish that selected NPC objects were spawned and not removed at tick 79 but absent from world lookup; they became visible by tick 120. The fixed 80-tick fixture checks could run before entity-section loading completed.
- Added an opt-in fixture bootstrap that derives forced regions from the existing run.mcfunction, waits for both areEntitiesLoaded and isPositionEntityTicking for every region chunk, then starts the original function. All Java probes now use the same fixture-relative clock. A 1200-server-tick timeout fails explicitly; readiness is required by the verifier. Existing NPC assertions and their relative timing remain unchanged.
- The readiness run (artifacts/repair-json-runtime248-ready.log) waited for 117 chunks until server tick 56 and exposed painting-variant as a remaining failure. Minecraft BlockAttachedEntity.tick checks support and discards hanging entities after 100 ticks; the upstream PaintingController and ItemFrameController replace that tick with NPC updates. The port used vanilla hanging ticks, allowing unsupported NPC paintings to disappear after the earlier test window.
- Added a server-side mixin skipping BlockAttachedEntity.tick only for registered hanging NPCs. Citizens already updates them through its registry. This matches upstream behavior for both protected and vulnerable NPCs and leaves ordinary paintings, item frames, glow item frames and non-hanging entities on their vanilla path.
- Nine runtime checks exercise painting/item-frame/glow-frame NPC survival after 105 direct ticks, another 105 ticks while vulnerable, and removal of matching unsupported vanilla controls. Vanilla drops are captured in the probe instead of entering the fixture world. Final validation follows below.

### 2026-09-12 17:46:36 +08:00 — Issue the first vanilla path request with its required ground state

- After hanging NPC repair, both artifacts/repair-json-hanging-runtime248.log and its -diagnostic rerun failed pathfind-minecraft-detour. Diagnostics show PathB remained at the start with navigation already stopped, and only one world entity carried that name, linked to the expected NPC. This rules out a duplicate/orphan actor explaining that observed failure.
- MCNavigationStrategy deferred its path request until update(), but setOnGround(true) ran earlier in its constructor. Physics could clear that flag before the request, causing GroundPathNavigation to refuse the path. Moved the existing ground-state initialization to immediately before the first actual request; it is not forced on subsequent navigation ticks. This preserves the upstream navigation precondition at the time it is needed.
- Added a direct regression probe that clears the ground flag after strategy construction, verifies vanilla refuses the same request, then verifies the deferred strategy request creates an active path without cancellation. Retained the original arena detour assertion and added fixture-relative path/identity diagnostics. Final results follow below.
- Final isolated NeoForge 21.1.248 runtime passes 107 NPC assertions and 181 probes (artifacts/repair-json-navigation-runtime248.log). The new navigation probe reproduces vanilla rejection after a ground reset and confirms successful deferred initiation; PathB then moves from its start, rounds the wall and passes the unchanged arrival assertion. Hanging survival/control checks and all JSON display/payment probes pass. Earlier failed runs remain retained as diagnostic evidence.
- Final dialogue audit: all 37 tests pass, including legacy show_text hover value arrays (artifacts/repair-json-dialogue248-final.log and artifacts/repair-json-dialogue-tests.xml).
- Final normal build and 124 ordinary tests pass (artifacts/repair-json-final248.log); release and source jars exclude all RuntimeAudit classes, including the fixture bootstrap.
