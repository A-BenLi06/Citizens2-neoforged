# GriefLogger SQLite packaging compatibility

Follow-up 2026-10-23, UTC+08:00: [normal shutdown recovery](../../docs/logging-shutdown-recovery-2026-10-23.md)
repairs two reproduced loss paths after the last tick: undrained SQL queues and
the separate per-player item buffer. Apply `server-stop-drain.patch` after `.4`,
then `player-quit-item-drain.patch`, to build `.6`. Native quit transfers the
original item buffer; SERVER_STOPPED waits for active SQL consumers and drains
both queues. Periodic attempts stay nonblocking and uncertain outcomes stay
fenced. A failed final drain reports uncommitted memory-only work. See the report
and manifest for actual-provider, normal-stop/restart and storage regressions.
This does not introduce crash spooling or accept integrated physical clients.

Follow-up 2026-10-23, UTC+08:00: [SQLite FULL recovery](../../docs/logging-full-recovery-2026-10-23.md)
repairs automatic-rollback queue suspension in `.3`. Apply
`sqlite-full-recovery.patch` after `reader-commit-recovery.patch` to build `.4`.
Recovery requires a pre-commit typed FULL, native rollback proof, no commit hook
and successful DEFERRED transaction restart. Uncertain outcomes stay fenced.
Acceptance: 93 actual-bytecode SQLite and 149 native/restart assertions, including
direct page-cap failure and reader/writer/normal commerce regressions. Physical
device loss, unflushed death, retention/load and MySQL remain separate gates.

Follow-up 2026-10-23, UTC+08:00: [reader-blocked COMMIT recovery](../../docs/logging-reader-recovery-2026-10-23.md)
repairs permanent queue suspension after an external reader releases its lock.
Apply `reader-commit-recovery.patch` after `queued-write-recovery.patch` to build
`.3`. Its typed DEFERRED SQLite BUSY/known-rollback exception preserves uncertain
outcome fences. Acceptance: 53 actual-bytecode SQLite assertions and 111 fresh
native/restart assertions across reader, writer and normal logging/commerce runs.
The provider and deferred client fixtures from earlier batches remain retained.

Follow-up 2026-10-23, UTC+08:00: [queued write recovery](../../docs/grieflogger-write-recovery-2026-10-23.md)
repairs a reproduced transient SQLite write loss in the packaged provider. Apply
`queued-write-recovery.patch` after the packaging patch below to build
`1.2.10-1.21.1-yuuniverse.2`. It adds owned ordinary/batch parameter snapshots,
rollback/retry and uncertain-outcome fences; it does change provider database/queue
source. The packaging-only history below describes version `.1`.

The `.2` build passes 27 actual-bytecode SQLite assertions, 37 native logging
checks and eight clean-restart checks. `run-storage-tests.ps1` requires JavaHome,
ProviderJar, Slf4jJar and a fresh OutputDirectory under task artifacts. The driver
is extracted from the provider's declared Jar-in-Jar path. See the linked report
and manifest for exact source/artifact evidence, rebuild provenance and remaining
in-memory durability/capacity/MySQL limits. No production activation is included.

The [native outage follow-up](../../docs/native-logging-storage-2026-10-23.md)
also validates `.2` with a real running-server SQLite writer lock, native ordinary/
container/drop queues and independent H2 logs: 22 outage + 11 restart assertions.
Its fresh normal 37 + 8 regression passes with the same unchanged provider JAR.

Target: GriefLogger 1.2.10 for Minecraft 1.21.1 / NeoForge, Java 21.
Source: <https://github.com/DAQEM/GriefLogger>, commit `e913b78806a8f7646b7e3adc0d6721d1b386f29d` (the checked 1.21.1 branch). Use this exact source commit: the upstream `1.2.10-1.21.1` tag currently points at source configured for another Minecraft version.

The upstream NeoForge artifact embeds `org.sqlite` at the root through Shadow. With Yuuniverse Economy's Jar-in-Jar SQLite module, Java rejects the split package before mods initialize. The patch changes SQLite from `shadowBundle` to Architectury Loom's `include`, producing NeoForge Jar-in-Jar metadata. It leaves the SQLite classes, native libraries and JDBC service descriptor inside the dependency JAR and allows one driver version to be selected. It changes no GriefLogger gameplay/database source or Economy code.

The custom artifact version is `1.2.10-1.21.1-yuuniverse.1`. Its dependency is SQLite 3.47.2.0 with range `[3.47.2.0,)`, which intersects Economy 0.4.1's `[3.46.1.3,)` range. The isolated stack selects 3.47.2.0. Keep both services' database validation in future upgrade acceptance.

## Rebuild in PowerShell 7

Clone the upstream project into a separate source directory, check out the pinned commit, and apply `sqlite-jar-in-jar.patch` with `git apply`. Point `JAVA_HOME` at JDK 21, then run from that checkout:

```powershell
.\gradlew.bat :neoforge:build --no-daemon --console=plain
```

The deployable output is `neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.1-neoforge.jar`; do not deploy the dev-shadow or sources JAR. The local deployment uses the existing `[自修改]` filename convention. Preserve upstream's Apache-2.0 license and source provenance when distributing the rebuilt artifact.

## Validation

- The unchanged release reproduced `ResolutionException: Modules grieflogger and org.xerial.sqlitejdbc export package org.sqlite` in a copy of the server's mod set.
- `:neoforge:build` succeeds; upstream has no tests in this source set. Archive inspection confirms no root `org/sqlite/` classes, a complete nested SQLite JAR, and selectable Jar-in-Jar metadata. The root JDBC service descriptor still contains the MySQL driver.
- Full-stack startup, JDBC initialization and shutdown evidence is recorded in the [integration installation record](../../docs/ecosystem-adaptation-goals-2026-10-13.md) and pinned hashes in the [artifact lock](../../docs/ecosystem-mods-1.21.1.lock.json). Those checks do not establish logging/rollback gameplay, MySQL, or real-client acceptance.

No module checks are disabled and no database driver is removed. When upstream publishes a compatible artifact, compare its packaging and rerun the combined-stack checks before replacing this build.
