# GriefLogger normal shutdown recovery

Recorded 2026-10-23, UTC+08:00. Advances ADAPT-09; full integration acceptance remains open.

GriefLogger `.4` could save the final player/world actions during normal server
shutdown while losing their logging history. Its periodic worker never received
another tick to flush the pending ordinary and batch SQL queues. A native stop
reproduction retained five block actions, one chest take and one dropped stack
in Minecraft storage, but SQLite retained only the initial chest placement and
JOIN. The final QUIT was also absent.

The first repair, `.5`, drained the SQL queues after native shutdown producers
finished. That same reproduction then retained five block records, one container
record and JOIN/QUIT, but still lost the drop. GriefLogger has another buffer in
each ServerPlayer mixin: item actions normally enter SQL on the next player tick.
Normal disconnect can occur before that tick.

The combined repair builds **1.2.10-1.21.1-yuuniverse.6**. Apply
[server-stop-drain.patch](../tools/grieflogger-compat/server-stop-drain.patch) after
the preceding `.4` FULL recovery patch, followed by
[player-quit-item-drain.patch](../tools/grieflogger-compat/player-quit-item-drain.patch).
The player quit event flushes its original owned item buffer through the same
method as the periodic player tick. Architectury SERVER_STOPPED then drains both
SQL queues synchronously, after native player disconnect/save events.

Provider `.5` source commit: `76670ee92f01d1a5774281d2af4fc9cacc9d748f`.
Provider `.6` source commit: `f35314d9947ea7cbc77df82a50ef41c5167954b0`.
Both author and committer are A-BenLi06 <ben_li06@outlook.com>; each source
checkout includes its timestamped walkthrough. Both builds succeed with Java 21.

Periodic queue execution remains nonblocking. A final drain waits for an active
consumer, then flushes the remaining original prefix. It does not wait for a queue
while holding the database monitor. Same-thread callback reentry is rejected;
the existing uncertain-commit fences remain intact. A failed final drain reports
uncommitted work. The database and queues retain their existing mod lifetime;
this change does not close/recreate repositories for each server session.

The native audit creates the final actions in ServerStoppingEvent, after the last
tick, and verifies committed records in ServerStoppedEvent. It never manually
flushes the player's drop buffer or final SQL writes. Queue frequency is 100;
the server stops around tick 20. Actual native chest clicks take seven named
paper, real placement uses stone/Create shaft/oak planks, and a native drop creates
three named snowballs with their original entity UUID. Independent H2 logging
retains the stone placement. The `.4` and `.5` failed worlds/logs/providers remain
preserved, and the same audit bytecode is used for `.6` acceptance.

The accepted stop begins with eight ordinary and two batch entries pending.
After native quit and the final drain, both queues are empty and all seven
gameplay records are visible once. JOIN/QUIT counts are one each after the first
stop and two each after the fresh-process reconnect and second stop. Full saved
inventory/chest/block/drop state stays equal in both stopped images.

The [validation manifest](logging-shutdown-validation-2026-10-23.json) records
provider commits, patches, exact rows/components, executable source hashes and
stopped world images. It independently decodes player inventory, original dropped
entity, four native block states and empty chest from saved NBT/region files.
The standalone runner substitutes only the Minecraft bootstrap logger owner;
storage/queue classes and the embedded SQLite driver come from the real provider
JAR. Native runs use the complete original Minecraft/provider runtime.

| Acceptance | Result |
|---|---|
| Actual provider and embedded SQLite | 109 assertions across 24 databases, including active-worker/final-drain waiting, late ordinary/batch writes, exact parameters, nonblocking periodic attempts, idempotence, locked/uncertain outcomes, same-process later admission and callback reentry |
| Native final-after-last-tick actions | 11 shutdown plus eight fresh-process restart/second-stop checks |
| Direct page-cap FULL regression | 27 failure/recovery plus 11 restart checks |
| Reader-blocked COMMIT regression | 22 plus 11 restart checks |
| Writer-lock regression | 22 plus 11 restart checks |
| Normal logging/rollback/commerce regression | 37 plus eight restart checks |

There are 168 fresh native/restart assertions. Every accepted invocation reaches
terminal BUILD SUCCESSFUL with no final-drain failure. Four completed-phase
launch attempts reject before server startup and preserve protected file hashes.
Eight fixtures and two stopped images pass exclusive world/database ownership.
The original ordinary packages, 828 ordinary tests per target and 100 Interactions
tests are explicitly reused for unchanged production/test/build sources; their
original hashes are verified. No fresh ordinary suite run is claimed. Production
classes match all seven native builds, while all 163 nested SQLite driver files
remain byte-identical to `.4` with selectable packaging and no root split package.

To reproduce, use Java 21 and the provider patch chain above. The existing
actual-provider SQLite runner requires a fresh artifact-confined output directory.
For native acceptance, prepare a fresh logging fixture, replace only its provider,
set queueFrequency to 100, then run `shutdown` and `shutdown-recover` with
`tools/logging-runtime-audit.gradle`. Retain localhost port 25632, logging-world,
fixture marker and separate build/cache directories. Completed shutdown phases
reject before launch even when prepareServerRun is skipped. Historical worlds
and snapshots must remain unchanged.

This establishes the tested dedicated-server normal-stop boundary. Pending work
still lives in memory if the final drain fails or the JVM is killed beforehand.
Crash spooling, physical device loss, uncertain-outcome administration,
retention/load, MySQL, arbitrary external producers after server stop and physical
integrated-client lifecycle remain separate acceptance. Independent Minecraft,
H2, SQLite and financial saves are not an atomic backup. Original migration
inputs, supported Paradigm inbound evaluation and deferred ordinary/VR/proxy
clients remain release gates. Production and the deferred BenLi06 client fixture
keep their previous artifacts; the full goal stays active.
