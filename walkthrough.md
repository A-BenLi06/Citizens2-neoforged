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
