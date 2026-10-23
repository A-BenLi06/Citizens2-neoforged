# Create final assembly process-kill recovery

Follow-up 2026-10-23, UTC+08:00: [basin mixing recovery](create-basin-recovery-2026-10-23.md)
adds six actual multi-item/fluid mixing kills on both targets, with 18 boundary
and 64 restart assertions. Other basin processes and machine-specific paths
retain their separate gates; this does not widen the assembly cases below.

Recovery evidence: 2026-10-20; final review: 2026-10-22, UTC+08:00.
Advances CRAFT-04/05 and ADAPT-05/11.
The full port and integrated release gates remain open.

## Fixture scope and authoritative boundaries

AssemblyCrashRuntimeAudit creates an intermediate through Create's real first-step
world press and retains its native sequence component. The final machine is a depot
press, connected moving belt press, deployer or spout. The selected KubeJS adapter
loads the two-step assembly on both startup and restart. An actual connected
synthetic player owns the native FTB team and the assigned final machine.

Before final processing, the fixture saves native machine/holder state, original
assignment, FTB definitions/team data and world independently. Motors are stopped
while saving; final processing uses real powered ticks or the native spout callback.
The deployer retains three flint; the spout retains 300 mB water. Native final output
is two paper with an exact custom-name component, deployment consumes one flint and
filling consumes 100 mB. Each case uses a separate evidence journal directory.

The runner kills the actual JVM at each boundary and restarts the saved fixture:

- PREPARED: the journal has durable admission before final item storage. Restart
  marks its missing native outcome REVIEW/MISSING. Reconciliation awards no credit.
  Reprocessing stays native, while the original unresolved observation fences quest
  admission. Authenticated rejection records a decision without inventing progress.
- CONFIRMED: complete native output and tool/fluid storage have returned; the journal
  is confirmed during FTB's actual task-completion callback. The world/FTB save is
  older. Explicit reconciliation projects the original absolute total once; retries
  and restored input cannot credit the completed task again.
- APPLIED: the journal records application, while world/FTB remain at the prior save.
  Restart retains applied evidence and old zero FTB progress. Reconciliation does
  not silently repair independent backup rollback. Reprocessing remains native but
  the retained applied total fences additional quest admission.

Every restart compares complete saved holder input with its exact components,
original native assignment/beneficiary and saved tool/tank state. The immutable
admission retains actual final units, parent ID/revision and final step; the entire
serialized assembly payload must equal the pre-kill payload. The task uses FTB's
persisted native default maximum, so two admitted output units cap at one native
task unit without changing evidence quantities.

FTB CustomTask.setMaxProgress changes runtime configuration and network sync; it does
not write that maximum to quest SNBT. An initial fixture with a maximum of two was
rejected on restart because its native definition reverted to one. The production
binding check correctly rejected that changed definition. The accepted fixture uses
a definition that survives restart instead of changing or bypassing binding checks.

## Runner and build isolation

run-machine-crash-audit.ps1 now supports both NeoForge versions and selected assembly
machines. It passes a fresh run token to the child JVM and uses a dedicated
artifacts/machine-crash-build directory and machine-crash-gradle-cache project
cache. Both paths must be isolated: Gradle's stale-output cleanup can delete the
previous build directory when the same project cache switches between output paths.
Killing requires the current run's marker,
a fresh Java process creation time and the exact isolated server argument-file
path. Windows escaped path separators are normalized for comparison. The runner
retains termination PID/time/token/entry, the pre-restart fixture state and separate
killed/recovered logs. Production JVMs and unrelated Gradle builds are outside this
selection. Failed fixture runs remain retained locally.

Example, from the repository root in PowerShell 7 after configuring Java 21:

```powershell
./tools/prepare-create-audit.ps1 -CreateJar artifacts/create-provider-pinned.jar -AssemblyCrashMachine deployer
./tools/run-machine-crash-audit.ps1 -JavaHome $env:JAVA_HOME -AssemblyMachine deployer -Stage prepared -NeoVersion 21.1.248
```

Run stages sequentially; the fixture world and audit build directory are shared.
Ordinary preparation without AssemblyCrashMachine removes the assembly-only script
so the existing native recipe/resource-reload audit remains independent.

The durable validator is tools/record-assembly-crash-validation.ps1. It rejects
missing kill markers, normal shutdown at the kill point, mismatched run/PID/entry
records, incomplete recovery logs and wrong saved/live item/tool/fluid state. It
requires every machine/stage on both targets and records hashes of sources,
provider, killed/recovered logs, termination records and saved/live payloads in
[create-assembly-recovery-validation-2026-10-20.json](create-assembly-recovery-validation-2026-10-20.json).
Use `-RegressionDate` to verify new ordinary/native regressions alongside the dated
recovery records without overwriting the original termination logs. Validation
checks exact component-bearing outputs, original beneficiary/machine/recipe/input,
prepared tool/tank equality and isolated process identity/time as well as log counts.

The interrupted session's older 21.1.248 native runs failed first because preparation
had not reset the reload script, then at an expected storage-exception assertion.
Fresh preparation and the tightened assertion pass on both targets. The assertion
now requires the intended exception message and a new observation/outcome pair;
an unexpected return reports its actual input and nearby items for diagnosis.
These reruns do not establish the cause of that earlier intermittent fixture failure.

Final validation on 2026-10-22: the validator accepts all 24 original process-kill
cases and 268 restart assertions. Both targets pass 263 native Create checks and
767 ordinary tests; the separate Interactions suite passes 100 tests. Six retained
binary/source archives exclude audit and foreign provider classes and preserve
Citizens/Interactions ownership. A new spout/PREPARED kill with the isolated project
cache passes 12 restart assertions while ordinary regression builds run separately.
Its termination/state/log hashes are recorded separately in `isolatedCacheRunner`.
The original kill-time source hashes remain in `recoverySources`; current validator,
runner and assertion hashes are in `sources`.

## Limits and remaining work

World, FTB and evidence files are separate durability domains. These cases prove
the stated actual-kill boundaries and retained older-save fences. They do not make
native products, fluids, tools, quest progress and money atomic. An operator must
reconcile the complete backup set; rollback never authorizes another monetary
generation or a payment reversal. Killing before admission can still lose an
observation. Arbitrary storage-interior kill points, repeatable/party/offline/reconnect
and financial backups, other machine paths, contraption copying, logging operations,
legacy provenance, supported Paradigm inbound formatting and physical VR/non-VR
integrated migration remain required. No production deployment or restart occurred.
