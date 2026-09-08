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
