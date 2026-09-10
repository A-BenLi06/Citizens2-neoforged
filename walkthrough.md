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
