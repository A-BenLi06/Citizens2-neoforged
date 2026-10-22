# Native FTB delivery outcome recovery

Date: 2026-10-22, UTC+08:00. Advances ADAPT-04/11.

Update: [exact journal record decoding](journal-record-decoding-2026-10-22.md)
rejects coerced schema/quantity/boolean types and duplicate members before
publishing history or appending restart recovery. Original damaged bytes remain
available for review; exact original backups reopen normally.

Update: [native completion callback validation](delivery-callback-recovery-2026-10-22.md)
reacquires original native targets and verifies the active actor session before
confirmation. Removed parties, task replacement and retired sessions retain the
existing REVIEW fence. Valid party changes and actual repeat reward resets keep
their original ownership and cycle semantics.

Citizens now retains a durable intent before calling the native consuming ItemTask
and its exact outcome after the provider returns. This covers `/citizens quest
submit`, Interactions `ftb_submit` and public script calls through
`FtbQuestTasks.submit`. Direct FTB task-screen submissions and other mods' calls
do not automatically participate in this journal.

FTB Quests 2101.1.36 updates progress inside `ItemTask.insert` before its caller
installs the reduced inventory stack. Minecraft player files, FTB team files and
Citizens records have independent save schedules. A successful native return
does not make those files an atomic transaction. Automatically retrying or
restoring items after interruption could duplicate progress, items or rewards.

## Retained contract

The world-relative `citizens/quest-deliveries` directory contains immutable JSON
revisions. Each retains the original actor, team/task/quest IDs, cycle, definition
digest, progress and full component-sensitive inventory image. Global sequence
and per-actor predecessor links detect gaps and changed histories within the
retained record set. An exclusive file lock and server-thread ownership prevent
competing writers. A file is forced before atomic publication; incomplete writes,
invalid histories and unavailable storage reject further delivery calls. Preserve
these files for review instead of deleting them to make an error disappear.

The normal transitions are PREPARED → CONFIRMED and PREPARED/CONFIRMED → REVIEW →
RESOLVED. An intent without a native outcome reopens as REVIEW. Confirmation also
updates a player persistent-data checkpoint which Minecraft saves with that
player's inventory. Admission checks the latest confirmed actor checkpoint and
the latest confirmed outcome for the requested team/task independently. An older
player checkpoint, regressed native progress/cycle or changed task definition
blocks delivery. Preflight detects these conditions without writing; execution
retains a review fence. Native repeat-cycle advancement can legitimately reset
progress and is distinct from rollback.

Pending records fence both the actor's inventory across tasks and the original
team/task across teammates. Completed retries consume nothing and create no new
intent. Native matching, partial quantities, components, completion and rewards
remain FTB-owned. The callback recursion guard still protects the inventory.
Editing/removing an unrelated old quest does not disable every future delivery;
the current task history and current actor checkpoint remain checked.

## Operator workflow

Administrators with `citizens.admin` can inspect entries:

```text
/citizens questdelivery list
/citizens questdelivery review
/citizens questdelivery inspect <delivery UUID>
```

Stop new attempts for the affected actor/team and preserve the journal, player
file, FTB team data and any associated reward/financial records. Compare the
retained component-sensitive before/after images with native state and available
consistent backups. Repair native state with its owning tools or restore a
consistent stopped-server backup when necessary. Do this before clearing review.

An online administrator can then record the decision and an explanation:

```text
/citizens questdelivery resolve <delivery UUID> accept <reason>
/citizens questdelivery resolve <delivery UUID> reject <reason>
```

Both decisions retain the original snapshots, operator UUID, time and reason,
and release this entry's fence. Neither decision changes inventory, task progress,
rewards or money. `reject` does not refund and `accept` does not replay consumption.
Console may inspect but cannot resolve without an online operator identity.
Other unresolved entries can still fence the next attempt.

## Acceptance and limits

Follow-up 2026-10-22, UTC+08:00: [indexed admission](delivery-admission-indexes-2026-10-22.md)
uses derived latest actor/task and ordered unresolved ownership without copying
all retained history per call. Older reviews never replace newer actor/task heads
or disappear when another review resolves. Original checkpoint/outcome validation,
forced publication and full-history reload validation remain required. Fresh
acceptance passes 302 native checks, 782 ordinary tests per target and 100 Interactions
tests; full loading/admin snapshot cost, other failures and migration/client gates remain.

Follow-up 2026-10-22, UTC+08:00: [delivery storage acceptance](delivery-storage-acceptance-2026-10-22.md)
adds 13 focused journal/storage tests per target and 17 native confirmation-write
failure/restart assertions. Previously consumed paper and progress remain intact;
review permits only the remaining delivery. A real-file probe preserves all chains
through 16,384 deliveries and records warm reload, lookup and allocation costs.
This adds bounded storage evidence, without automatic retention or production
latency/full-device-failure claims.

Follow-up 2026-10-22, UTC+08:00: [saved checkpoint history](delivery-checkpoint-history-2026-10-22.md)
also validates every present player checkpoint against its retained owned native
outcome before admission. A valid older journal prefix can otherwise appear fresh
or end with RESOLVED while losing a newer confirmed player outcome. Those cases
require a consistent backup; an absent historical row cannot be resolved through
the ordinary review command. The fresh history/native regression passes 285
checks, ordinary775 per target and Interactions100. Removal/rollback of both
checkpoint and journal together and general retention/load remain outside that
positive-evidence check.

Ordinary clean builds pass 775 tests on each of NeoForge 21.1.233 and 21.1.248;
the separate Interactions suite passes 100. The journal adds eight unit tests.
The native normal run passes 17 checks. Six actual JVM kills pass three boundary
assertions apiece and a total of 40 restart assertions. The existing selected
script/party/reward/commerce workflow passes 90 checks, and the native quest,
recipe, saved-item and migration regression passes 141.
Create's existing native regression passes 263 checks on each supported NeoForge
target, including the unchanged craft-definition digest used by retained evidence.

The isolated 21.1.248 native fixture uses the installed ItemTask implementation,
actual saved players/teams and audit-only pauses around the provider's methods.
Normal checks cover partial 2+3 consumption, components, recursive callbacks,
completed retry, a failure after native inventory mutation, read-only/execution
fences, console rejection, explicit resolution and a stale player checkpoint.

The forced-termination matrix saves different native domains deliberately:

| Kill boundary | Saved paper | Saved progress | Expected recovery |
|---|---:|---:|---|
| Before native consumption | 3 | 0 | Missing outcome requires review |
| After progress, before remainder installation | 3 | 3 | Missing outcome requires review |
| After inventory mutation, before confirmation | 0 | 0 | Missing outcome requires review |
| After confirmation and both native saves | 0 | 3 | Retained outcome allows only the remaining two paper |
| Confirmation with older player file | 3 | 3 | Player checkpoint mismatch requires review |
| Confirmation with older task file | 0 | 0 | Task outcome mismatch requires review |

The runner validates a fresh run token, actor/entry identity, exact isolated Java
argument file and process creation time before forced termination. Recovery must
reacquire the original team, retain components and preserve both saved native
owners when rejecting a retry. Resolution has no implicit native side effects.
Exact counts, source/provider/package hashes and kill/recovery records are in
[the validation manifest](ftb-delivery-validation-2026-10-22.json).

This is conservative uncertainty handling, not power-loss atomicity or automatic
cross-domain repair. Back up the journal together with player/team/reward/ledger
data while stopped. Whole-history removal, arbitrary external inventory changes,
uncoordinated world/financial backups and direct submissions outside this adapter
are outside this bounded acceptance. Wider machine/logging recovery, original
legacy content and physical VR/non-VR integrated release acceptance remain open.
The ordinary-client fixture retains its separately pinned earlier artifacts.
No production deployment or restart is part of these checks.

## Reproduce the isolated delivery checks

Use PowerShell 7 with Java 21 configured in `JAVA_HOME`, from the repository root:

```powershell
./tools/prepare-delivery-audit.ps1
./neoforge/gradlew.bat -p neoforge --project-cache-dir ../artifacts/delivery-native-gradle-cache -I ../tools/delivery-runtime-audit.gradle '-Pneo_version=21.1.248' runServer --console=plain
./tools/run-delivery-crash-audit.ps1 -JavaHome $env:JAVA_HOME -Boundary progress -LogPrefix delivery-progress
```

Run crash boundaries sequentially against this fixture. Both build output and
Gradle project cache are isolated from ordinary builds. The init script adds the
provider pause mixin only to the audit output; ordinary release archives exclude
it and all audit/provider implementation classes. The six retained ordinary
source/binary archives preserve separate Citizens and Interactions ownership.
