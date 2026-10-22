# Explicit FTB craft bindings and retained progress

Update 2026-10-22, UTC+08:00: [craft recovery isolation](craft-recovery-isolation-2026-10-22.md)
attempts valid original task/generation scopes even when another target is invalid.
Remaining failures retain their evidence and report original task/team/cycle
context. Repairing a target resumes only its outstanding projection.

Update 2026-10-22, UTC+08:00: [exact journal decoding](journal-record-decoding-2026-10-22.md)
rejects altered numeric/type/duplicate-member evidence and unsupported outcome
names before startup recovery. An invalid optional outcome cannot silently become
a missing native return. Valid historical Create evidence remains unchanged.

Citizens now has a production consumer for supported native crafting observations.
An online administrator binds a **CustomTask** to the currently loaded recipe ID
and revision. This uses a custom task because FTB's ordinary crafting item task
matches output items independently and cannot enforce an exact recipe ID.
The binding records its creator UUID, task/quest IDs, native maximum, repeat policy
and a stable epoch. Recipe changes or task definition changes require an explicit
content migration; repeated identical binding requests retain the same epoch.

```text
/citizens craftevidence bind <FTB task hex ID> <namespaced recipe ID>
/citizens craftevidence list
/citizens craftevidence review
/citizens craftevidence reconcile
/citizens craftevidence resolve <entry ID> accept <reason>
/citizens craftevidence resolve <entry ID> reject <reason>
```

Every operation checks current `citizens.admin` authority. Binding needs an active
player's native team context, and review needs an authenticated player UUID.
Console may list and reconcile but cannot invent a review actor. Both command and
service enforce permissions; Paradigm denial/revocation applies to elevated sources.
These are admin operations, not dialogue actions that grant quest-editing authority.

Records live in the current world at `citizens/quest-evidence/bindings` and
`citizens/quest-evidence/journal`. Back them up together with that world's FTB data.
The single-writer lock excludes another service over the same directory.
Immutable, forced files preserve the [journal history](quest-evidence-journal-2026-10-18.md).
Incomplete binding writes stop that service and remain available for examination.
Opening validates persistent data but never modifies FTB progress. The service
starts without FTB; provider-dependent binding requests fail before admission.

## Admission and projection

Only an actual supported table/player-grid result take for the exact recipe
revision is admitted. Full output count/components and original actor/team are
retained before native consumption. Inventory assignments, purchases, pickups,
and an identical result from another recipe do not supply this evidence. Returned
native consumption confirms evidence; interrupted/unknown processing goes to review.
Unresolved review prevents further admission to that generation until an attributed
decision is recorded.

Projection reacquires FTB's public file/task/team objects and writes the absolute
confirmed total, capped at the bound maximum, through `TeamData.setProgress`.
Only then are journal entries marked APPLIED. This preserves native task events
and never replaces FTB's custom checker, writes private fields, grants an output
or issues currency. The admitted team UUID prevents later party changes from
redirecting retained evidence.

Repeatable generations use FTB's count of completed resets. Native reward claim/reset
advances that count and permits a new generation. A non-repeatable completion-count
flip is still the same run, with cycle zero. Old generations cannot project into
new cycles. Removed teams/tasks, changed maximum/repeat/quest definitions and
counter regression require review.

`reconcile` retries CONFIRMED projections. It accepts the last journal-applied total
or the exact desired total already stored by FTB, so retries never add units twice.
Other native progress values fail explicitly. APPLIED evidence is not replayed
automatically after FTB/world rollback: rollback is an operator reconciliation
boundary, not authorization to restore items, rewards or progress. Review decisions
retain the reason and operator UUID.

## Acceptance and remaining work

The actual-provider fixture checks pickup/shift, components, same-output recipe
isolation, recipe redefinition, remainder failures, review fencing, native
completion/reset cycles, permission denial/revocation, immutable binding reopen
and absolute replay. Release archives exclude fixture classes. Exact counts and
artifact/provider hashes are in `artifacts/craft-task-projection-validation-2026-10-18.json`.

Explicit binding, durable generations, authenticated review and absolute CustomTask
projection are implemented. No atomic transaction between player/world/FTB files
and the journal is established. Actual process-kill/power-loss and coordinated
rollback acceptance, repeat-safe Economy monetary rewards, commerce task bindings,
machine adapters, legacy task-ID migration and physical VR/non-VR clients remain
open. Fixture reward claims verify FTB repeat reset, not currency payments.
CRAFT-04/05 and ADAPT-04/05 remain incomplete until those required gates are met.
