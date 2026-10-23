# GriefLogger recovery after reader-blocked COMMIT

Recorded 2026-10-23, UTC+08:00. Advances ADAPT-09; complete integration acceptance remains open.

GriefLogger `.2` retained writes through a writer lock but permanently stopped
automatic flushing when an external reader blocked COMMIT. The retained standalone
baseline executes the actual provider and bundled SQLite driver: execution succeeds,
COMMIT reports SQLITE_BUSY, rollback succeeds, the reader releases its snapshot,
and retry still leaves the original observation pending. A fresh Minecraft server
with the same `.2` provider reproduces the failure in its native background worker;
native player actions finish, but queues never drain after unlock.

The incremental [provider patch](../tools/grieflogger-compat/reader-commit-recovery.patch)
builds **1.2.10-1.21.1-yuuniverse.3** from the preceding `.2` source commit
`ca00b1ac2baa71fc4baab035420d2e5bd21f0bb1`. It permits retry after a successful
rollback when a direct Xerial SQLite connection reports typed SQLITE_BUSY and
both the current and next configured transaction modes are DEFERRED. Error-message
matching and unconditional commit retries are avoided.

Provider source commit: `5c3ed69d4bddfdc3b2451f9a7d1ef7e7b8d3b19b`, with author
and committer A-BenLi06 <ben_li06@outlook.com>.

SQLite documents that a reader-blocked COMMIT leaves its transaction active.
However, the bundled Xerial commit method executes COMMIT and then begins another
transaction. BEGIN IMMEDIATE/EXCLUSIVE can fail after a successful COMMIT. Requiring
the DEFERRED path matters: BEGIN DEFERRED only changes a flag and acquires no lock.
The implementation retains the uncertain-outcome fence for other modes, wrappers,
drivers, errors, failed rollback and unreconstructable legacy batches. This change
does not provide a general outcome-reconciliation or administration API.

The SQLite compile dependency in common is compile-only. Both platforms reference
the same declared SQLite version; NeoForge still includes its selectable nested
driver. No root SQLite split package is introduced, and all 163 files inside the
nested driver remain byte-identical to `.2`. Fabric packaging retains its existing
configuration; this acceptance targets NeoForge.

The [validation manifest](logging-reader-recovery-validation-2026-10-23.json)
records the following checks and exact source, provider, log and database evidence:

| Acceptance | Result |
|---|---|
| Actual provider bytecode and SQLite | 53 assertions: original 27 cases, ordinary/batch reader-blocked commits, exact binary/null parameters, mixed transaction-mode fences, and BUSY after a successful real commit through a wrapper |
| Native reader lock | 22 checks and 11 clean-restart checks on NeoForge 21.1.248 |
| Native writer-lock regression | 22 checks and 11 clean-restart checks in another fresh world |
| Normal native logging/commerce | 37 checks and eight clean-restart checks in another fresh world |
| Stopped databases and packages | Six SQLite quick_check results, independent H2/world ownership, exact original rows/financial receipt/balance, unchanged Citizens/Interactions production classes |

The two storage fixtures use actual chest clicks, native placements of stone, a
Create shaft and later oak planks, and a native component-bearing dropped stack.
During the lock, eight ordinary and four batch entries remain pending. NeoProtect
continues independently through H2. After unlock, both GriefLogger queues drain;
all seven original block/container/item records survive without duplicates. The
complete exposed inventory/chest contents, four block states and original dropped
entity UUID/components remain equal through failure, retry and clean restart.
The normal fixture separately retains complete burst histories, rollback behavior
and the original Economy operation/transaction/shop/currency/balance.

Only the standalone suite substitutes the Minecraft bootstrap/logger owner. Native
runs use the real provider initialization, logger and workers. The native baseline
and repaired tests use frozen audit sources and separate worlds/build/cache paths.
Existing 825 ordinary tests per target and 100 Interactions tests are explicitly
reused for unchanged production/dialogue/test/build sources with hashes verified;
no fresh ordinary test run is claimed. Historical evidence is preserved.

To reproduce, apply the existing packaging patch, queued-write-recovery.patch,
then reader-commit-recovery.patch. Build with Java 21. The existing standalone
runner accepts the resulting `.3` JAR and a fresh artifact-confined output path.
For native acceptance, prepare a fresh logging fixture, replace its `.1` provider
with `.3`, and run `storage` then `storage-recover` through the logging init script
with `-PloggingAuditLock=reader`. Writer remains the default; the lock option accepts
only reader/writer. Keep build outputs and project caches separate, and preserve
the marker, localhost port 25632 and logging-world guards. Completed runs reject
before launch. Use separate fixtures for writer and normal native/recover phases.

Pending writes remain in memory. Unflushed termination, full-device/write-interior
faults, uncertain-outcome administration, retention/capacity/load, MySQL and wider
producer combinations remain open. Independent world/log/financial saves are not
atomic. Original migration inputs, the supported Paradigm inbound contract and
deferred physical ordinary/VR/proxy checks remain required. The repaired provider
is prepared locally; production and BenLi06's stopped acceptance fixture keep
their previous artifacts. The full goal remains active.

SQLite transaction semantics: <https://www.sqlite.org/lang_transaction.html>.
The fetched reference and bundled-driver commit bytecode are retained with the
baseline evidence. The manifest records the exact provider source commit and
requested author/committer identity.
