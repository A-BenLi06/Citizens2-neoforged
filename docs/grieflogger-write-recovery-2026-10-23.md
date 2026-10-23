# GriefLogger queued write recovery

Recorded 2026-10-23, UTC+08:00. Advances ADAPT-09; full integration acceptance remains open.

The selected GriefLogger 1.2.10-1.21.1-yuuniverse.1 discards a queued observation
when SQLite temporarily refuses a write. Its queue clears the pending statements
before execution; the database catches the error and closes the failed statement.
A real second SQLite connection holding BEGIN IMMEDIATE reproduced zero rows after
unlock and retry, where the original observation required one. The original JAR,
driver, reproducer source, log and synthetic database remain retained.

The [provider patch](../tools/grieflogger-compat/queued-write-recovery.patch)
builds version **1.2.10-1.21.1-yuuniverse.2**. Native repositories now queue owned
SQL and parameter snapshots, including every batch row and copied binary values.
Each flush creates fresh JDBC statements, retains its original queue prefix until
commit, and rolls back failed execution. Concurrent producers append without
clearing each other's work; flush ownership and database transaction ownership
prevent overlapping queue attempts. Initialization commits schema before events
register, and ping closes its JDBC resources.

A known rollback permits complete retry. Ambiguous commit or failed rollback
retains pending work and stops automatic flush across both queues, with an explicit
error. An old caller supplying a driver-owned batch also requires review after
failure because the driver may discard its batch parameters. All native repository
producers use the new snapshots. Cleanup failure after commit never replays the
committed prefix. No observation, component or financial ownership is invented.

Provider source commit is `ca00b1ac2baa71fc4baab035420d2e5bd21f0bb1`, based on the
existing packaging commit `0017e15321badb496e4939c58ec7a60cf3821139` and upstream
`e913b78806a8f7646b7e3adc0d6721d1b386f29d`. Author and committer are
A-BenLi06 <ben_li06@outlook.com>. The exported patch applies cleanly to the
retained original source. Apache-2.0 provenance and selectable SQLite packaging
are preserved. The nested driver ZIP has different container metadata, while all
163 contained files, including classes, native libraries and services, are identical.

The [validation manifest](grieflogger-write-recovery-validation-2026-10-23.json)
records these completed checks:

| Acceptance | Result |
|---|---|
| Rebuilt NeoForge provider | Successful Java 21 build; no root SQLite split package |
| Actual provider bytecode and SQLite | 27 assertions; lock/unlock recovery, exact immutable binary/null parameters, complete ordinary/batch rollback and retry, lost commit acknowledgment, failed rollback, legacy cleanup/batch fences and 600 concurrent distinct rows |
| Native NeoForge 21.1.248 regression | 37 checks: real player actions, complete burst histories, container components, world rollback and original Economy commerce ownership |
| Clean native restart | Eight checks retain both histories, exact components and original operation/transaction/balance |
| Stopped ownership/integrity | World, H2 and two SQLite databases are exclusively accessible; SQLite quick_check is ok, postings balance and original receipt/balance agree |
| Citizens/Interactions provenance | All 1,020 Citizens and 74 Interactions production classes match the native build; six ordinary archives retain accepted hashes |

The standalone fault tests execute the actual provider JAR and its embedded SQLite
driver. Only the Minecraft bootstrap/logger owner is substituted; SQL, queue,
transaction and driver code run unchanged. The native regression separately uses
real mod initialization and native actions. It does not inject those faults into
the running Minecraft server. Existing 825 ordinary tests per target and 100
Interactions tests are explicitly reused for unchanged Citizens production,
dialogue, tests and build configuration; their retained logs/reports/packages are
hash-verified, without claiming fresh ordinary runs.

Use [the test runner](../tools/grieflogger-compat/run-storage-tests.ps1) with JDK 21,
the rebuilt provider JAR, an SLF4J API JAR and a fresh output directory under
artifacts. The runner extracts the driver through its actual Jar-in-Jar metadata.
The existing native logging init script accepts loggingAuditFixture under artifacts,
preserving its marker, loopback port 25632 and logging-world guards. Build and
project-cache directories remain separate. A missing default Economy artifact and
an initial invocation failed before launch. The corrected fixture pins the retained
Economy 0.5.6 JAR; the rejected invocation log is retained separately.

Pending writes remain in memory. Unflushed JVM termination, full-device failures,
uncertain-outcome administration, capacity/retention/load, MySQL and wider native
failure combinations remain acceptance work. The tests do not establish an atomic
world/log/financial backup or a general release gate. The new JAR is prepared and
tested locally; production and the pinned, deferred BenLi06 client fixture retain
their previous artifacts. Original migration and physical ordinary/VR/proxy gates
remain open.
