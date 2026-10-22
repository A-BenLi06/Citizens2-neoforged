# Recover valid crafts alongside invalid native targets

Recorded 2026-10-22, UTC+08:00. Advances CRAFT-05 and ADAPT-04/05/11.

Craft reconciliation previously stopped at the first failed projection. A changed
task maximum or unavailable native scope could therefore prevent an administrator
from recovering unrelated confirmed crafts, including crafts whose original
beneficiaries are offline. Repairing every earlier target was required before
the valid work could be reached.

The retained baseline reproduction uses three actual vanilla crafting result
takes. Each consumes one log and produces four planks. A LOWEST result listener
changes the corresponding native CustomTask maximum after durable admission;
the returned outcome becomes CONFIRMED and its projection correctly rejects the
changed target. The fixture saves original evidence, team and player data, then
restarts without a contributor online. Two maxima are changed through the native
API while the third remains valid. On `7105171`, reconciliation reports the first
failure and leaves all three tasks at zero. The original sources, actual failed
assertion, retained histories and observation are preserved in task artifacts.

Reconciliation now snapshots confirmed task/generation scopes and attempts each
original scope. Successful scopes leave the batch. Pending scopes are retried only
after a successful scope, allowing native completion to unlock other work without
guessing a dependency graph. Each successful pass removes at least one scope;
a pass without progress stops. Existing binding, cycle, absolute-total, native
availability and callback-target checks still decide whether each projection is
valid. Reconciliation never recrafts the item or repeats input consumption.
FTB retains ownership of progress and any resulting native completion callbacks.

After attempting valid scopes, administrator reconciliation reports remaining
failures with original task, team, cycle and generation identities and their
causes. Additional failures are suppressed on the first contextual exception.
Failed evidence remains CONFIRMED for later repair. A failed call may therefore
have recovered valid scopes; repeating it considers only the remaining evidence.
The successful return value still counts projected task/generation scopes.

Fresh native acceptance passes seven seed and eleven restart/recovery assertions
on each of NeoForge 21.1.248 and 21.1.233, 36 checks total. The initial recovery
reports both invalid scopes while restoring the third task to one. Repairing one
scope recovers it while the other remains reported; the final repair processes
only the remaining scope, and a retry is a no-op. Original observations, actor,
recipe, quantities and outcomes are retained. Rejoining the original actor proves
all inventory slots/components are unchanged by offline recovery. The stopped
native team files independently retain all three task totals at one.

The unchanged broader Create fixture passes 263 checks on 21.1.248 in a fresh
directory, including ordinary machine, batch, transport, basin, fluid, deployer,
fan and assembly behavior. Both clean ordinary builds pass 823 tests with no
failures/errors/skips. The earlier 100 Interactions results remain attributed to
their original run; dialogue and audit sources are unchanged, and no new run is
claimed. Six ordinary archives exclude audits/foreign implementations, preserve
separate mod ownership, match final source and contain the same changed class
bytes tested by the corresponding native runs, including Projection.

All final jobs reached terminal success. Four fixture worlds and 25 journal locks
are exclusively stopped. Exact source, provider, package, native-save, journal,
before/final observation and log identities are in the
[validation manifest](craft-recovery-isolation-validation-2026-10-22.json).

The opt-in craft-recovery init accepts isolated build/fixture/phase properties and
requires its marker, loopback port 25646 and world craft-world. The existing Create
init additionally accepts machineAuditFixture under artifacts, preserving its
default, marker and original port/world checks. This keeps new regressions away
from historical fixtures. Ordinary archives include neither audit source set.

These cases establish isolation between independent crafting scopes. They do not
establish every crafting dependency graph, machine crash boundary, financial/world
backup combination or provider failure. Recovery/retention, original migration
inputs, supported Paradigm inbound evaluation and deferred physical ordinary/VR/
proxy acceptance remain open. Production and the pinned client fixture are unchanged.
