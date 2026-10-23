# NeoProtect / GriefLogger native audit

Use PowerShell 7 and Java 21. Prepare the pinned providers with
`tools/prepare-logging-audit.ps1`, then run from the repository root:

```powershell
./neoforge/gradlew.bat -p neoforge -I ../tools/logging-runtime-audit.gradle '-Pneo_version=21.1.248' runServer --console=plain
./neoforge/gradlew.bat -p neoforge -I ../tools/logging-runtime-audit.gradle '-Pneo_version=21.1.248' '-PloggingAuditPhase=recover' runServer --console=plain
```

The native phase creates fresh synthetic actors and exercises item placement,
door multi-placement, chest use/clicks, entity damage, a placement/break burst,
container/block rollback and a finite Economy coordinate-shop trade. Recovery
uses the exact saved actor, operation and transaction after a real clean server
restart. Existing fixture histories are retained; assertions filter by actor UUID.

The fixture is `artifacts/logging-audit-server`, loopback port 25632, with an
explicit marker. It contains NeoProtect 1.0.4, repaired GriefLogger 1.2.10,
Architectury 13.0.11, Config Lib 1.1.8, Create performance-v6 and Economy 0.5.6.

For a separate fresh acceptance world, pass `-PloggingAuditFixture=../artifacts/your-fixture`
and isolated loggingAuditBuildDir/project-cache paths. The directory must remain
under task artifacts and retain the same marker/port/world guards. The
[2026-10-23 GriefLogger recovery batch](../../docs/grieflogger-write-recovery-2026-10-23.md)
uses this path selection to validate provider `.2` without replacing historical
or deferred client fixtures.

Economy requires NeoForge 21.1.247+, so this combined fixture uses 21.1.248.
The NeoForge 21.1.233 build and absent-provider regression remain separate.

The init script adds the probe only to the dialogue source set. Run a standard
`clean test build` afterward and inspect release JARs to exclude the probe and
provider classes. Logs and synthetic databases stay under ignored `artifacts/`.

For actual in-server SQLite lock/retry acceptance with the rebuilt GriefLogger `.2`,
use a fresh loggingAuditFixture and the phases `storage` then `storage-recover`.
The [native storage report](../../docs/native-logging-storage-2026-10-23.md) records
22 + 11 checks for native block/container/drop producers and independent H2 logging.
Completed phases reject before launch, including when prepareServerRun is skipped.
The shared marker/port/world/state guards execute directly on runServer as well
as preparation. Keep build outputs and project caches separate; retain completed
fixture histories.

For reader-blocked COMMIT, use the repaired `.3` provider and pass
`-PloggingAuditLock=reader` to the same storage/storage-recover phases in a fresh
fixture. Writer is the default. A reader starts
an explicit transaction and reads the actual blocks table before native producers
run. The [reader recovery report](../../docs/logging-reader-recovery-2026-10-23.md)
records the `.2` failure and `.3` acceptance, plus independent writer and normal
regressions. Preserve earlier providers, source checkouts and completed worlds.

For actual page-cap `SQLITE_FULL` recovery, use provider `.4` and
`-PloggingAuditLock=full` in another fresh fixture. The selector accepts
writer/reader/full. Setup commits separate-actor padding to exhaust existing
block/container/item leaf capacity, then caps the real provider connection.
Native gameplay observations fail directly in their existing tables; lifting
the cap must drain their original queues. Padding is retained under its own
actor and counted separately. The physical device is not filled. The
[FULL recovery report](../../docs/logging-full-recovery-2026-10-23.md) records the
`.3` reproduction, `.4` native/restart acceptance, actual-bytecode suite and
reader/writer/normal commerce regressions. Keep outputs/caches separate and
preserve historical fixtures and completed-phase launch guards.

For final native actions after the last tick, use provider `.6` in another fresh
fixture and configure its queueFrequency to 100. Run `shutdown` then
`shutdown-recover`. ServerStoppingEvent creates native actions before disconnect;
ServerStoppedEvent verifies SQL queues, all seven gameplay records and final QUIT.
No manual final item-buffer/SQL flush is performed by the audit. The fresh process
restores original native storage and verifies each record once through a second
normal stop. Completed phases reject even with preparation skipped. Retain both
stopped world/database images and the original `.4`/`.5` failed fixtures. See the
[shutdown recovery report](../../docs/logging-shutdown-recovery-2026-10-23.md).

For coordinate indexes, prepare a fresh copied stopped fixture with
`tools/prepare-logging-index-audit.ps1 -FixtureDirectory <artifact-path> -ProviderJar <provider-.7>`.
Run `indexes` then `indexes-recover`. Setup records every original row; the audit
commits 2,048 fixture-owned SQL rows per history table through the real provider
and checks exact coordinate plans, ordered payloads, idempotence and restart.
This workload is separate from native gameplay observations. The configuration
case uses `-DisableIndexes`, `indexes-disabled`, then enables useIndexes before
`indexes-recover`. Only the copied fixture's legacy index is removed in disabled
setup. Fresh/completed-phase guards run directly before launch too. Keep separate
build/cache directories and preserve all stopped images and original fixtures.
See the [index report](../../docs/logging-coordinate-indexes-2026-10-23.md).

For repository transaction isolation, prepare a fresh copied stopped fixture
through `tools/prepare-logging-index-audit.ps1` with provider `.8`, then run `reads`
and `reads-recover`. All nine actual repository APIs must wait for an active
transaction to commit or roll back, return exact committed rows/components, and
retain the same results in a new process. The fixture SQL workload has its own
identity and does not replace gameplay observations. Completed phases reject
even with preparation skipped. See the [read report](../../docs/logging-read-isolation-2026-10-23.md).

For native MySQL command storage, use provider `.9` with a fresh isolated MySQL
database and logging fixture. Configure the provider's ordinary MySQL settings;
keep credentials outside tracked evidence. Run `mysql-commands`, then
`mysql-commands-recover`. The audit posts actual Minecraft commands through a
synthetic connected player and checks exact UTF-8 strings, ownership, prior rows,
column attributes and restart. It retains observed failures before asserting,
so truncated pre-fix results remain reviewable. `mysql-schema-reject` requires
`logging-mysql-schema-original.json` containing original `column` and
`allCommands` observations; it verifies explicit initialization rejection and
unchanged data/attributes. Completed phases reject even with preparation skipped.
Use `tools/capture-logging-mysql-fixture.py` only for this task's identified Docker
fixture to retain independent SQL snapshots and dumps. See the
[MySQL report](../../docs/logging-mysql-command-storage-2026-10-23.md) and its
manifest for exact runners, local fixture paths and bounded acceptance.

For an actual forced termination after observed native histories and saved
player/world state:

```powershell
./tools/run-logging-crash-audit.ps1 -JavaHome $env:JAVA_HOME -LogPrefix logging-crash-248
```

The runner uses separate build/project-cache directories, a fresh run token and
the exact fresh JVM argument-file path. It retains the termination record and
killed/recovered logs; recovery requires the same saved run identity. See
`docs/logging-crash-recovery-2026-10-22.md` for the accepted 37 + 8 assertions.

This covers bounded burst retention and the stated flushed/saved process-kill
boundary. Queue overflow, termination during writes, timed history expiry,
unrestricted world/financial rollback, production performance and physical clients
remain separate acceptance.
