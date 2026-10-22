# Keep saved delivery checkpoints backed by retained outcomes

Recorded 2026-10-22, UTC+08:00. Extends
[native delivery recovery](ftb-delivery-recovery-2026-10-22.md).

A stopped journal restore can leave a valid older global prefix while a newer
player file retains a checkpoint for an absent delivery. Native reproductions on
`582359f` exercise an actor missing entirely from that prefix and an actor whose
latest prefix entry was explicitly RESOLVED. Neither case is a fresh player.
Checkpoint comparison previously ran only for a latest CONFIRMED actor record;
the restored prefix therefore allowed another consumption and replaced the saved
checkpoint without retaining its original outcome.

Admission must validate any persisted checkpoint against its original retained
entry before preparing or consuming anything. The entry must belong to the
current actor and retain a confirmed native outcome, including an outcome later
retained through explicit review/resolution. Missing, malformed, wrong-typed,
foreign-owner or outcome-less references cannot be interpreted as a first
delivery. Existing pending, latest-actor and original-team/task checks continue
to apply independently.

This validation does not fabricate a historical intent, change another actor's
record, erase the player's checkpoint or mark an unknown delivery resolved.
Restore a consistent journal/native backup containing the original outcome before
retrying. Where the original row is absent, there is no truthful row for the
ordinary resolution command to approve. A matching stopped backup allows a new
native delivery chained to the original predecessor. An intact unrelated actor
and a genuinely new actor without a checkpoint retain their normal behavior.

The opt-in history runner creates a fresh loopback fixture, executes actual native
deliveries and saves their player/team data. It retains both the earlier prefix
and full immutable journal, restores only the prefix while stopped, then checks
admission without changing the saved inventory/checkpoint. For the RESOLVED
case, a real native inventory-tail failure and authenticated operator resolution
precede the later confirmed delivery lost by the restore. A final whole-world
seed snapshot restores matching original history and native saves for a successful
new delivery. Each mutated world remains preserved for inspection.

Retention must preserve checkpoint-backed outcomes and their required history;
this work adds no automatic pruning or guessed retention period. If both the
checkpoint and journal have been removed or rolled back together, this positive
evidence check alone cannot establish the missing history. Wider coordinated
backup/retention, storage/load/large-history throughput and migration/client
acceptance still have their own gates. The deferred BenLi06 fixture stays pinned
and stopped; production activation is outside this batch.

Final validation passes 21 absent-actor and 24 latest-RESOLVED history checks.
Both before-fix native runs consumed the remaining paper, advanced progress and
replaced the unbacked checkpoint. Final runs reject both preflight and execution
without changing that actor's inventory, progress, checkpoint or journal count.
Malformed, foreign-owner and wrong-typed references reject; unrelated/fresh actors
continue; matching whole-world backups allow one new original-predecessor outcome.

Regression passes 61 callback-mutation, 17 normal delivery and 104 selected
PREPARED/XMod/commerce/party checks, plus six actual JVM kills with 18 boundary and
40 recovery assertions: 285 native checks including the new history matrix.
Ordinary builds pass 775 tests on each NeoForge target and separate Interactions
passes 100, with no failures/errors/skips. Six release archives exclude audit
classes/mixins and foreign implementations, preserve mod ownership and match the
final production source and tested native class bytes. Eight stopped fixtures
cover 14 world locks; Economy's immutable read-only integrity, balanced postings
and original prepared-operation/transaction identity pass. Exact evidence is in
[the validation manifest](delivery-checkpoint-history-validation-2026-10-22.json).

Reproduce sequentially with Java 21 and PowerShell 7:

```powershell
./tools/run-delivery-history-audit.ps1 -JavaHome $env:JAVA_HOME -Shape absent -LogPrefix history-absent
./tools/run-delivery-history-audit.ps1 -JavaHome $env:JAVA_HOME -Shape resolved -LogPrefix history-resolved
```

The runner uses fresh artifact fixtures, isolates build/cache locations and checks
stopped world ownership before every restore. Do not run concurrently with other
delivery audits sharing its build directory or loopback port.
