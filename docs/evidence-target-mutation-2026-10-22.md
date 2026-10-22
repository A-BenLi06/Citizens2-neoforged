# Retain evidence when native teams or callback targets disappear

Recorded 2026-10-22, UTC+08:00. Extends
[offline PREPARED recovery](commerce-prepared-recovery-2026-10-22.md).

Two native reproductions on `eb5666f` delete an actual solo FTB party through its
public leave operation: first before receipt reconciliation, then inside the
actual task-completion event. FTB retains the party's TeamData in both cases.
Citizens incorrectly marks the original commerce receipt APPLIED without
reporting that its native party no longer exists. Original operation, transaction,
request, actor and team identities remain in the retained before-fix fixtures.

The shared evidence adapter now checks FTB Teams' public manager for the admitted
team UUID independently of FTB Quests' retained TeamData. It never creates a
replacement party or redirects evidence to the actor's current team. The same
lookup protects craft projection and delivery recovery reads.

Before setting native progress, projection captures the serialized quest/task/
reward definition revision. After native callbacks return, it resolves the current
file, team, task and quest again. Every object must retain its original identity,
and the definition revision, quest ID, repeat policy, cycle, maximum, exact total
and required completion must still agree. A deleted team, replaced object or
definition edit prevents APPLIED acknowledgment. Already executed native callback
effects are retained; Citizens does not attempt to undo arbitrary scripts.

The original financially settled receipt remains CONFIRMED for review/retry.
Deletion is still reported on repeated reconciliation. A restored matching
definition or stable replacement with the original public ID and matching bound
definition may be reconciled in a later projection attempt. That attempt independently
validates financial evidence and native state. Money and inventory delivery are
never replayed by projection. Existing ACCEPTED monetary entitlements retain
their original beneficiary and settlement rules.

The opt-in `teamMutationAudit` fixture exercises `removed`, `callback`,
`definition`, `replacement` and `normal` modes through actual native completion
events and public mutation methods. It retains exact receipt attribution, checks
retry behavior and verifies unchanged balances/transaction counts and balanced
postings. A replacement with a changed maximum stays CONFIRMED until its original
definition is restored. An identical replacement can legitimately recover through
the existing independently validated dependency retry within the same administrator
call; it need not wait for another command. The standard XMod fixture also
exercises normal craft evidence and repeat rewards.

Final validation passes 25 mutation/control, 104 selected integration, 277 commerce
reward graph, 46 monetary restore and 17 native delivery checks: 469 native checks
in total. Both ordinary NeoForge 21.1.248/21.1.233 builds pass 775 tests each;
separate Interactions passes 100. Six ordinary release archives preserve separate
ownership, exclude probes/foreign implementations and match final source/native
class bytes. Thirteen stopped databases pass immutable read-only integrity,
balanced-posting and original operation/transaction checks after exclusive fixture
access and absence of SQLite sidecars were verified. Exact results and identities
are recorded in [the validation manifest](evidence-target-mutation-validation-2026-10-22.json).

Two draft fixture diagnostics are retained. A `title` readData probe changed no
serialized definition in this FTB release; the final probe changes and verifies
an actual serialized property. A byte-identical replacement legitimately recovered
through another validated projection in the same command; the final changed-
maximum replacement remains held until its original definition is restored.
Neither diagnostic required a production workaround.

This fence detects changes visible when a callback returns. It cannot undo
external effects or observe a definition changed and restored entirely within a
callback. Separate item-delivery submission callbacks, storage/retention/load,
remaining crash boundaries, original migration provenance and physical ordinary/
VR/proxy acceptance still require their own gates. The deferred BenLi06 fixture
stays pinned and stopped. No production activation or new process-kill result is
claimed.
