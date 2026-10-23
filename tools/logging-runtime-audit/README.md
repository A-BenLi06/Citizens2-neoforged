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
