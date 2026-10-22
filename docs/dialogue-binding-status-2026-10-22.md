# Correct dialogue status for name triggers

Date: 2026-10-22, UTC+08:00.

The ordinary-client fixture exposed a misleading status: its two working
`NPC named` conversation triggers were reported as “0 NPC(s).” The command used
the numeric-ID map's size as a count of all NPCs. Reload used the same wording.

Both responses now report numeric NPC ID and case-insensitive NPC name counts
separately. A conversation indexed by two names and no numeric IDs reports
“0 NPC ID(s), 2 NPC name(s).” This counts the configured triggers without implying
that ID and name references necessarily identify different live NPCs. Existing
`npcCount()` semantics remain numeric; the name count has a separate accessor.
The responses use native translation keys with English fallbacks. Trigger lookup,
dialogue execution, progress and persistence are unchanged.

The separate Interactions suite passes all 100 existing tests on NeoForge
21.1.248; its standalone JAR builds and contains no Citizens, FTB or audit
implementation classes. Hashes and counts are in
[dialogue-binding-status-validation-2026-10-22.json](dialogue-binding-status-validation-2026-10-22.json).
The running physical-client fixture remains pinned to its recorded earlier
artifacts; this readout-only follow-up is not hot-replaced or deployed.
