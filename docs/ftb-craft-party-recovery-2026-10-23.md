# Shared craft recovery across party changes and repeat cycles

Recorded 2026-10-23, UTC+08:00. Advances CRAFT-05 and ADAPT-04/05/11; extends
[independent craft recovery](craft-recovery-isolation-2026-10-22.md) and
[party reward acceptance](ftb-party-reward-acceptance-2026-10-22.md).

Both NeoForge 21.1.248 and 21.1.233 now exercise two native connected ServerPlayer
sessions contributing actual crafting-result takes to the same recipe-bound FTB
CustomTask. Native FTB invite/join/leave operations own membership. TeamData owns
progress, native CustomReward claims and repeat resets. Each target uses three
separate server processes and an independently saved world.

The seed admits one four-plank craft from each member. A LOWEST result callback
changes the task maximum after durable admission, leaving both returned outcomes
CONFIRMED while projection rejects the invalid definition. The second member
also leaves through FTB's native party operation in that callback, before native
ingredient consumption returns. Both original actors' admissions retain the
original party/generation; the former member's personal task stays at zero.

On restart, both contributors are offline. Reconciliation projects their combined
eight units once into the original party and marks both entries APPLIED. It does
not require current party membership from the original actors or consume inputs
again. New player instances reconnect with their original UUIDs, inventories and
current native teams. Full component-sensitive inventories are unchanged by offline
recovery, including an independently named/damaged item belonging to each player.

The former member crafts four units in the personal scope, rejoins the original
party, and crafts another four there to complete the shared total of twelve.
Personal and party progress retain separate evidence generations. Native shared
reward claim resets the party to cycle one; new actual takes by both members give
that cycle eight units. A second fresh process preserves the partial repeat cycle,
both native members, all six APPLIED observations and both full inventories. One
new member craft completes that cycle, and the other member claims its native
reward, advancing to cycle two at zero progress.

At each membership/repeat/restart boundary the fixture replays original result and
outcome notifications, reconstructed from retained native observations. It requires
their complete re-encoding to equal the original admitted bytes before posting.
Those notifications neither recraft items nor generate new observation IDs. They
cannot move old personal/party evidence into the actor's current scope or grant
progress in a later repeat cycle. The final seven unique native crafts retain
28 original units: party cycles zero/one have twelve each; personal cycle zero has
four. No automatic APPLIED-history replay restores progress after a reset.

FTB CustomTask's maximum is runtime owner configuration, not a stored quest-file
field. The first draft assumed otherwise and correctly failed its restarted
definition fence; all six immutable journal/binding records remained unchanged.
The final fixture writes its own task ID/maximum definition once and registers
that definition through FTB's public CustomTaskEvent before quest loading. Each
restart asserts the native initialization event configured it exactly once.
Recovery never substitutes the journal's expected maximum for a missing owner.
The failed world, sources, bytecode inspection and original log remain retained.
An earlier compilation also rejected an unhandled checked native leave exception;
its diagnostic is retained and the harness now propagates rejection as a failure.

Each target passes ten seed, 23 recovery and twelve final-restart assertions:
90 fresh party assertions total. Separate fresh runs of the original independent
recovery audit pass seven seed plus eleven recovery checks per target, another
36, for 126 total native assertions. All final jobs reach terminal success.
Production sources already satisfy these workflows, so no gameplay patch was
needed for this batch.

Independent stopped-save inspection decodes the original player UUIDs and every
inventory slot/component from compressed native player NBT in all six saved-world
images. FTB quest/team SNBT independently confirms each phase's original party
membership, repeat count and task total. Original observations, actors, admissions,
outcomes, generation identities and immutable histories are checked separately
from the runtime PASS markers.

Four rejected launches include completed party phases with preparation skipped;
all protected native/state/property files retain their hashes. Preparation rejects
existing and outside-artifact destinations. Shared fixture/property checks run
both during prepareServerRun and directly before runServer. Completed phase
prerequisites preserve accepted worlds. See the
[audit instructions](../tools/craft-recovery-runtime-audit/README.md) and
[fresh fixture preparation](../tools/prepare-craft-party-audit.ps1).

All six ordinary archives retain the preceding accepted hashes and separate mod
ownership. Their 1,023 Citizens/74 Interactions production classes match each
target's party and original-recovery builds; audit classes match across all four
builds. The earlier 828 ordinary tests per target and 100 Interactions tests are
explicitly reused for unchanged production/test/build sources, with their original
log/report/package hashes verified. No fresh ordinary test execution is claimed.
Outputs and project caches are separate under artifacts. Twelve world locks and
44 journal locks, including retained images, the earlier failed fixture and
the unchanged deferred BenLi06 client fixture, are exclusively stopped.

Exact source/provider/log/binary/native-save/guard identities are in the
[validation manifest](ftb-craft-party-validation-2026-10-23.json). This verifies
clean native save/restart and bounded two-member permutations. It does not claim
physical client input, arbitrary concurrent network traffic, Economy monetary
payments, forced termination, inconsistent backups or every party/dependency/
machine combination. Retention/device loss, original migration inputs, supported
Paradigm inbound evaluation, deferred ordinary/VR/proxy acceptance and integrated
release remain open. Production has not been activated or restarted; the full
goal remains active.
