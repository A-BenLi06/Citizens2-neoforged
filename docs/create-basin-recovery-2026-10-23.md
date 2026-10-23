# Create basin mixing process-kill recovery

Recorded 2026-10-23, UTC+08:00; runs started 2026-10-22.
Advances CRAFT-04/05 and ADAPT-05/11.

The accepted assembly and saw recovery cases did not establish recovery of a
basin's combined item/fluid consumption and output storage. The new opt-in audit
uses a KubeJS-registered native mixing recipe, a real powered mixer/cogwheel/motor,
a basin and an assigned connected synthetic beneficiary. It invokes native ticks;
it does not manufacture result events or apply a recipe directly.

The saved basin contains five snowballs, two flint and 750 mB water. One native
operation consumes two snowballs, one flint and 250 mB water, stores two paper
with an exact custom-name component and produces 100 mB lava. Unused inputs remain.
Before processing, the motor is stopped and native assignment, complete ordered
basin slots/tanks, FTB definitions/team state and world are saved. Each machine/
stage/version case has its own fresh world and evidence directory. The FTB task
uses its persisted default maximum of one; two admitted output units cap at one
task unit while the original evidence retains the full quantity.

The runner forcibly terminates the actual isolated JVM at three boundaries:

- PREPARED: admission is forced before native item/fluid mutation. Restart restores
  every saved slot/tank and marks the original entry REVIEW/MISSING. Reconciliation
  grants no credit. Reprocessing remains native, but the unresolved original entry
  fences new credit. Authenticated rejection changes no basin contents or progress.
- CONFIRMED: native item/fluid storage has returned; the journal is CONFIRMED in
  the native FTB completion callback, before APPLIED. World/FTB remain at the older
  save. Explicit reconciliation projects the original absolute total without
  consuming basin inputs, retry is a no-op, and restored inputs cannot credit the
  completed task again.
- APPLIED: the journal contains APPLIED while the native saves remain older.
  Restart retains zero native progress and the original applied evidence.
  Reconciliation does not silently repair independently rolled-back native data;
  the applied total fences further quest credit while native mixing still returns.

Every recovery verifies original entry, actor/team, machine assignment, recipe
revision and the entire serialized multi-item/fluid evidence payload. Native
processing independently checks exact retained items, component-bearing output,
consumed water and produced lava. Original admissions remain identical through
all revisions. Stopped native FTB files independently agree with the observed
final task totals.

Six actual JVM kills pass on NeoForge 21.1.248 and 21.1.233: 18 boundary and 64
restart assertions. Each kill requires a fresh run token, marker PID/entry, fresh
Java process creation time and the exact isolated argument-file path. Termination
receipts, killed/recovery logs, state/payload, original journal revisions and
provider hashes remain retained. The runner's new optional fixture/build/cache
parameters keep these cases away from historical worlds. Project caches remain
outside cleanable build output; outside-artifact paths and a contained cache reject
before launch or path creation. Existing defaults and assembly selection remain.

Normal Create preparation removes the basin-only recipe. A fresh ordinary Create
regression passes all 263 checks after this mode transition, including existing
table/machine/batch/press/transport/basin/fluid/deployer/fan/assembly behavior.
The existing 825 ordinary tests per target and 100 Interactions results from the
preceding binding-validation batch are explicitly reused: production/dialogue/test
sources and build configuration are unchanged and their logs/reports/packages are
hash-verified. No fresh ordinary test run is claimed. Every ordinary packaged
production class matches the corresponding new native build; selected-version
classes also match the fresh full Create regression. Ordinary archives continue
to exclude runtime audits and preserve separate mod ownership.

Evidence and exclusive stopped ownership of seven worlds and 43 journals are recorded in the
[validation manifest](create-basin-recovery-validation-2026-10-23.json).
The durable validator is tools/record-basin-crash-validation.py; it checks exact
saved/live/recovered storage, unchanged admissions, persisted native totals,
process identity/time, immutable histories and package/source provenance.

For a fresh fixture under artifacts, using PowerShell 7 and JDK 21:

```powershell
$fixture = Join-Path $PWD 'artifacts/my-basin-prepared-server'
./tools/prepare-create-audit.ps1 -CreateJar artifacts/create-provider-pinned.jar -BasinCrash -Fixture $fixture
./tools/run-machine-crash-audit.ps1 -JavaHome $env:JAVA_HOME -ProcessingMachine basin -Stage prepared -NeoVersion 21.1.248 -Fixture $fixture -BuildDirectory (Join-Path $PWD 'artifacts/my-basin-build') -ProjectCacheDirectory (Join-Path $PWD 'artifacts/my-basin-cache') -LogPrefix my-basin-prepared
```

Run cases sequentially on the pinned loopback port 25629/world create-world.
The interrupted first orchestration used a dotted explicit log prefix; parameter
validation stopped it before JVM launch. Its preparation and diagnostic remain
retained; the corrected matrix uses hyphens and fresh fixtures.

These checks cover this multi-item/fluid mixing path and its three durable
boundaries. Arbitrary storage-interior kills, other basin processes, fan/fluid/
deployer/transport paths beyond accepted cases, contraptions, repeat/party/offline
and financial/world-backup combinations retain their own gates. World, FTB,
evidence and money remain separate durability domains. Migration provenance,
supported Paradigm inbound evaluation and deferred physical ordinary/VR/proxy
acceptance remain open. No production or pinned client fixture was changed.
