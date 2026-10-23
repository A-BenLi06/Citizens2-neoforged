# Create fan world, depot and belt process-kill recovery

Recorded 2026-10-23, UTC+08:00. Advances CRAFT-04/05 and ADAPT-05/11.
Acceptance passes 18 verified JVM kills, 72 boundary and 216 restart assertions
across NeoForge 21.1.248 and 21.1.233. Full Create regression passes 263 checks.

The preceding native fan checks established actual airflow, rolls, remainders and
complete storage, but did not establish process-kill recovery of fan world entities,
depot buffers or pending belt items. FanCrashRuntimeAudit adds those three native
holder paths using the KubeJS-registered SPLASHING recipe and real motor/fan ticks.
Depots and connected belts use their actual native storage callbacks. The audit
neither posts synthetic crafting events nor directly applies a recipe.

A named stack of three snowballs is saved while motors are stopped. Native bulk
splashing rolls each input, producing six paper with an exact output name and
three diamonds. The durable evidence retains all six original rolled result
stacks; native storage may merge compatible stacks. The world path also saves
and verifies original input entity UUID and no-gravity state. Depot/belt snapshots
retain all exposed contents, including pending belt removals/insertions, without
using a mutating serialization operation as an inspection API.

Before processing, the fixture saves native input, original fan assignment,
FTB definitions/team state and world. Each holder/stage/version has a separate
fresh world/evidence directory. The task's persisted default maximum is one;
its credit cap does not reduce the six original admitted output units.

Three forced JVM boundaries apply independently to every holder:

- PREPARED: the original admission is durable before native primary/secondary
  storage. Restart restores the exact named input, marks REVIEW/MISSING and awards
  zero credit. Native reprocessing still produces all outputs while review fences
  further quest admission. Authenticated rejection changes no native storage or
  task progress.
- CONFIRMED: all native storage has returned; the original journal is CONFIRMED
  during FTB's actual completion callback, before APPLIED. Restart retains older
  zero native progress. Explicit reconciliation restores the original absolute
  task total without processing input; retry is a no-op. Reprocessing restored
  inputs cannot credit the completed original task again.
- APPLIED: the journal contains APPLIED while native saves remain older. Restart
  retains zero native progress and the original applied evidence. Reconciliation
  does not silently repair independently rolled-back native state. Native
  reprocessing returns all outputs while the applied total fences further credit.

World confirmation requires the original input UUID to hold the primary paper and
the secondary diamond to exist as a separate native entity. Depot and belt cases
require both output types in their holder and no world overflow. Every recovery
compares the entire original evidence payload, actor/team, task, binding epoch,
generation/cycle, machine assignment, current recipe revision and quantities.
Original admissions remain equal through all three immutable revisions. Stopped
FTB files independently agree with the observed final totals.

The accepted assertion counts per NeoForge target are:

| Holder | PREPARED boundary/restart | CONFIRMED boundary/restart | APPLIED boundary/restart |
|---|---|---|---|
| World entities | 3 / 13 | 4 / 13 | 5 / 12 |
| Depot | 3 / 12 | 4 / 12 | 5 / 11 |
| Belt | 3 / 12 | 4 / 12 | 5 / 11 |

Each termination requires the
current run token, marker PID/entry, fresh Java process creation time and exact
isolated server argument-file path. State, killed/recovery logs, termination
receipts, original histories and provider hashes remain retained. Build outputs
and project caches remain separate and confined to task artifacts.

The runners now accept fan-world/fan-depot/fan-belt processing selections and
world/depot/belt fan recipe preparation. Preparation rejects simultaneous machine
families and removes fan-only recipes for a normal regression fixture. Existing
basin/assembly selections and defaults remain available. Fan runs wait for the
native item chunk to tick before starting; they do not replace restored entities
or bypass native holder processing.

The durable validator, tools/record-fan-crash-validation.py, checks actual native
versions, exact original and recovered storage, process identities/times,
immutable admission histories, original binding/generation scope, stopped native
task totals, providers, and packaged class bytes. It verifies unchanged ordinary
test/report/package evidence separately from fresh native runs.

The [validation manifest](create-fan-recovery-validation-2026-10-23.json) records
the exact original six rolled stacks and their components, all process/log/provider
identities and exclusive stopped ownership of 19 worlds and 103 journals. Every
accepted process reached terminal success. Three conflicting machine-family
selections reject before fixture creation; the fresh full regression also verifies
that normal preparation removes the fan-only recipe.

Production, dialogue, ordinary test sources and build configuration are unchanged.
The previous 825 ordinary tests per target and 100 Interactions tests from
[binding validation](craft-binding-validation-2026-10-22.json) are explicitly reused,
with retained log/report/package hashes checked. No new ordinary test run is claimed.
All 1,020 Citizens and 74 Interactions packaged production classes per target match
their respective new native builds; selected-version classes also match the fresh
full Create regression. The six ordinary release archives retain their accepted
hashes, audit exclusions and separate mod ownership.

For a fresh fixture under artifacts in PowerShell 7 with JDK 21:

```powershell
$fixture = Join-Path $PWD 'artifacts/my-fan-belt-prepared-server'
./tools/prepare-create-audit.ps1 -CreateJar artifacts/create-provider-pinned.jar -FanCrash belt -Fixture $fixture
./tools/run-machine-crash-audit.ps1 -JavaHome $env:JAVA_HOME -ProcessingMachine fan-belt -Stage prepared -NeoVersion 21.1.248 -Fixture $fixture -BuildDirectory (Join-Path $PWD 'artifacts/my-fan-build') -ProjectCacheDirectory (Join-Path $PWD 'artifacts/my-fan-cache') -LogPrefix my-fan-belt-prepared
```

Cases run sequentially on the pinned loopback port 25629/world create-world.
These SPLASHING cases do not establish every fan mode, storage-interior boundary,
overflow configuration, contraption, party/repeat/offline or financial-backup
combination. World, FTB, evidence and money remain separate durability domains.
Original migration provenance, supported Paradigm inbound evaluation and deferred
physical ordinary/VR/proxy acceptance remain open. Production and the pinned
ordinary-client fixture are outside this batch.
