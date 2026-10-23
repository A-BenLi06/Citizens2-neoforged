# GriefLogger recovery after SQLite FULL abort

Recorded 2026-10-23, UTC+08:00. Advances ADAPT-09; complete integration acceptance remains open.

GriefLogger `.3` permanently stopped flushing when SQLite automatically aborted a
write transaction with `SQLITE_FULL`. The native database had rolled back the
entire prefix, but Xerial still reported JDBC manual-commit configuration. The
provider's second rollback failed because no transaction remained. Restoring
capacity left the original writes pending and automatic flushing stopped.

The retained standalone reproduction executes the actual provider and embedded
driver against a real SQLite file capped with `PRAGMA max_page_count`. It observes
the native rollback callback, zero committed rows, two pending writes and the
failed second rollback. After the cap is lifted, `.3` still writes nothing.
A fresh NeoForge server reproduces the failure through native producers and the
actual provider worker. The physical drive is never filled.

The incremental [provider patch](../tools/grieflogger-compat/sqlite-full-recovery.patch)
builds **1.2.10-1.21.1-yuuniverse.4** from `.3` source commit
`5c3ed69d4bddfdc3b2451f9a7d1ef7e7b8d3b19b`.
Provider source commit: `1ae929ef9858be76c7ab13f0b44d993e903659a0`, with author and
committer A-BenLi06 <ben_li06@outlook.com>.

Each serialized flush temporarily observes Xerial's native commit/rollback hooks.
A typed `SQLITE_FULL` raised before the provider enters COMMIT may recover only
when the native rollback hook has fired, no commit hook has fired, and current
and next configured transaction modes are both DEFERRED. Starting a fresh native
DEFERRED transaction must succeed before retry is allowed; the provider restores
the driver's corresponding transaction-mode and first-statement state. Listeners
are removed in `finally`. Original owned SQL/parameter snapshots remain pending
and are replayed together by the existing queue mechanism.

Error-message matching is not used. A successful new BEGIN also proves that an
unexpected active transaction cannot be silently reused. Failed restart, other
drivers/wrappers/modes, uncertain commits and driver-owned legacy batches keep
their conservative fences. A commit hook does not prove durable success; FULL
during COMMIT is not newly retried. The preceding narrow DEFERRED reader-BUSY
recovery remains in place.

The [validation manifest](logging-full-recovery-validation-2026-10-23.json)
records exact source, patch, provider, log, state, database and package evidence:

| Acceptance | Result |
|---|---|
| Actual provider and embedded SQLite | 93 assertions: preceding 53 checks; repeated ordinary/batch FULL aborts; original prefix/null/owned 32 KiB binary/later writes; integrity; mixed-mode, post-commit FULL and legacy batch fences |
| Native page-cap failure | 27 checks plus 11 clean-restart checks on NeoForge 21.1.248 |
| Native reader-BUSY regression | 22 checks plus 11 clean-restart checks in a fresh fixture |
| Native writer-BUSY regression | 22 checks plus 11 clean-restart checks in another fresh fixture |
| Normal native logging/commerce | 37 checks plus eight clean-restart checks in another fresh fixture |

The native FULL fixture fills existing block/container/item leaf capacity using
committed padding owned by a distinct synthetic fixture actor. Both the padding
connection and provider connection use the bounded page cap. Native chest clicks,
stone/Create-shaft placements, a component-bearing snowball drop and later oak
planks hit FULL directly in existing logging tables. Gameplay observations retain
the real actor and original parameters. Padding stays in the stopped fixture and
is independently counted; it is never presented as gameplay history. No trigger
or replacement gameplay observation is used in the accepted fixture.

During the outage, original ordinary/batch entries remain pending while NeoProtect
independently retains the actions through H2. Lifting only the provider's capacity
cap lets its worker/explicit flush drain the original queues. All seven gameplay
records persist once, with equal complete inventory, chest, four block states and
original dropped-entity UUID/components through retry and restart. Normal commerce
acceptance separately preserves the original Economy operation, transaction,
shop, currency, balanced ledger and balance through restart.

An earlier trigger fixture remains retained as a failed setup. FULL inside its
trigger took a different transaction path, and an independent cleanup transaction
encountered a lock. Its world, original sources and log remain intact. It does not
establish the repaired auto-abort case. The accepted direct fixture uses identical
audit bytecode for `.3` failure and `.4` acceptance.

To reproduce, apply packaging, queued-write-recovery, reader-commit-recovery and
sqlite-full-recovery patches in order, then build with Java 21. Use the existing
standalone runner with `.4` and a fresh artifact-confined output directory. Native
acceptance uses a fresh logging fixture with `.4`, `storage` then `storage-recover`
and `-PloggingAuditLock=full`. The selector accepts writer/reader/full. Keep build
outputs and project caches separate; retain marker, localhost port 25632 and
logging-world guards. Completed phases reject even when preparation is skipped.

The selected production classes match the preceding ordinary packages. The 828
ordinary tests per target and 100 Interactions tests are explicitly reused for
unchanged production/test/build sources, with original artifact/log/report hashes
verified; no fresh ordinary run is claimed. All 163 nested SQLite driver files
remain byte-identical to `.3`, with selectable Jar-in-Jar metadata and no root
SQLite split package. Fabric packaging is retained; acceptance targets NeoForge.
Production and BenLi06's deferred, stopped client fixture keep previous artifacts.

These checks exercise actual SQLite capacity failure through bounded page caps.
Physical device loss, IOERR, FULL during COMMIT, unflushed JVM termination,
uncertain-outcome administration, retention/capacity/load, MySQL and wider producer
combinations remain open. Queues remain in memory; independent world/log/financial
saves are not atomic. Original migration inputs, supported Paradigm inbound
evaluation and deferred ordinary/VR/proxy clients remain release gates. The full
goal stays active.

SQLite references: [transaction error handling](https://www.sqlite.org/lang_transaction.html)
and [native rollback hook](https://www.sqlite.org/c3ref/commit_hook.html).
