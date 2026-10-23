# Native logging during SQLite write failure

Recorded 2026-10-23, UTC+08:00. Advances ADAPT-09; integrated release remains open.

The preceding [GriefLogger repair](grieflogger-write-recovery-2026-10-23.md)
established rollback/retry through actual provider bytecode and SQLite outside
Minecraft. This follow-up verifies a real SQLite outage inside the running
NeoForge 21.1.248 server, with GriefLogger `.2`, its own initialization/logger/
workers, native player actions and independent NeoProtect H2 storage. No provider
implementation or logger/bootstrap owner is substituted in these native runs.

A fresh synthetic player joins, places a chest and commits initial metadata. A
second real SQLite connection then holds BEGIN IMMEDIATE while the player opens
the chest, takes seven named paper, places stone and a Create shaft, and drops
three named snowballs. Native item ticking creates the actual dropped-item batch.
The fixture uses a 20 ms busy timeout to exercise failed flushes without waiting
for the driver's default timeout on each server tick.

Both worker-thread and server-thread attempts reach real SQLITE_BUSY errors and
the provider's known-rollback path. An independent database reader sees only the
original seed history. Ordinary and batch queues retain their work; NeoProtect
continues to flush both placements and the actual container take to H2. Another
native plank placement appends during the outage without losing the earlier
prefixes. Before release, eight ordinary and four batch queue entries remain.

After the second connection rolls back its lock, native workers/explicit flushes
drain both queues. Public GriefLogger lookup returns each of the three placements
once, one seven-paper container removal with its original component, and one
three-snowball drop with its original component. The full actor-specific block,
container and item history projection has seven rows, including the earlier chest placement and its native
interaction. Original timestamps and component bytes remain unchanged on retry.
NeoProtect independently agrees with all three placements.

The entire exposed player inventory and chest inventory, four exact block states,
original dropped-item UUID/no-gravity state and exact item components remain equal
through failed attempts, successful retry and no-op retry. The saved player/world
and both histories survive a fresh server process without replaying item effects
or duplicating SQL rows. This is a clean restart after successful retry, not a
forced termination with unflushed events.

The [validation manifest](native-logging-storage-validation-2026-10-23.json)
records 22 outage checks and 11 restart checks. After changing the shared launch
guard, a separate fresh normal regression also passes 37 native checks and eight
restart checks, including original Economy operation/transaction/balance and both
complete burst histories: **78 fresh native assertions** in total.

The runner previously placed its fixture guards only on prepareServerRun. Retained
logs show that task becomes UP-TO-DATE on restart, which skips those guards. The
same marker, loopback-port, world and saved-state checks now also execute directly
before runServer launches. Completed storage and storage-recover phases reject
before launch. Four negative invocations verify both preparation and direct launch
paths, including explicitly skipping preparation. Seven protected world/database/
state/log files retain their exact hashes after all four rejections.

LoggingRuntimeAudit dispatches the two opt-in phases to LoggingStorageRuntimeAudit;
ordinary native/crash/recover behavior remains available. Use the existing logging
fixture preparation and pinned provider `.2` instructions, then pass
`-PloggingAuditPhase=storage` followed by `-PloggingAuditPhase=storage-recover`,
with a fresh artifact-confined loggingAuditFixture and separate loggingAuditBuildDir
and project-cache directories. Port 25632, logging-world and the fixture marker
remain mandatory. Completed fixture histories are preserved rather than rerun.

Both fixture worlds, both H2 files and four SQLite databases pass exclusive stopped
ownership checks. SQLite quick_check returns ok for all four databases, with no
sidecars. The outage fixture has no financial transactions; the normal regression
retains its one original receipt and balance with balanced ledger postings. All
1,020 Citizens and 74 Interactions packaged production classes match both native
builds; all six ordinary archives retain accepted hashes. Previous 825 ordinary
tests per target and 100 Interactions tests are explicitly reused for unchanged
production/dialogue/test/build sources, with retained evidence hashes verified.
No fresh ordinary test run is claimed.

Provider `.2` and Citizens production code are unchanged in this batch. Unflushed
termination, full-device/write-interior failures, uncertain-outcome administration,
retention/capacity/load, MySQL and other native producer combinations remain open.
The complete provider fixture uses 21.1.248; the 21.1.233 core baseline remains
separate. Original migration/provider contracts and deferred physical ordinary/VR/
proxy acceptance remain required. Production and the pinned BenLi06 client fixture
retain their previous artifacts and stopped state respectively.
