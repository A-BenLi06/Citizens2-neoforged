# Crafting outcomes and quest evidence journal

Update 2026-10-23, UTC+08:00: [craft admission indexes](craft-admission-indexes-2026-10-23.md)
derive observation/generation/task reads after validating histories and companions.
Gameplay avoids unrelated retained history while administrative snapshots retain
their ordering. Immutable schemas, replay owners, review fences, durable-write
ordering and query-time checked totals remain required. No history is pruned.

Update 2026-10-22, UTC+08:00: [craft binding validation](craft-binding-validation-2026-10-22.md)
adds companion validation under exclusive journal ownership, after complete
history loading and before PREPARED recovery writes. A rejected companion leaves
durable history unchanged and releases ownership. Public journal opening retains
its existing behavior; the craft service supplies its binding validator.

Follow-up: [explicit craft task projection](ftb-craft-evidence-projection-2026-10-18.md)
now supplies production recipe bindings, durable team/quest/cycle generations,
absolute CustomTask projection and authenticated review commands. It supersedes
the pending production-consumer clauses below; monetary rewards and actual
process-kill/world-rollback acceptance remain open.

`CraftingOutcomeEvent` pairs the immutable observation UUID from
`CraftingResultEvent` with the native result slot's return outcome. The required
ResultSlot mixin wraps the original methods; native matching, ingredient removal,
remainders and exceptions remain owned by Minecraft. Shift-craft observations are
paired with their subsequent take. Unmatched observations expire as UNCONFIRMED.

RETURNED means `ResultSlot.onTake` returned normally after its native consumption
and remainder loop. INTERRUPTED means the native call threw. Neither establishes
that player/world files were saved; RETURNED also precedes some menu output/drop
handling. Listener failures are contained so they cannot interrupt that native tail.
Registry-aware evidence serialization preserves components, empty grid cells and
the admitted recipe definition/revision.

## Journal contract

`QuestEvidenceJournal` is a single-writer, owning-thread API for validated evidence.
The caller supplies an explicit stable binding epoch, native team UUID, quest ID
and native completion cycle. The same scope/cycle reuses its durable generation
UUID; a lower observed cycle requires reconciliation. The binding epoch must be
persisted by its owner, rather than regenerated at startup.

Admission is deduplicated by observation UUID, generation UUID and native task ID.
Reusing that identity with a changed payload is rejected. Admission retains actor,
recipe ID/revision, units and the immutable serialized evidence. The API trusts the
calling adapter to validate current actor/team membership and task binding.

Each transition appends a new immutable revision file. Writes force a newly created
temporary file before an atomic move to a new target. The loader validates schema,
identities, contiguous transition history and generation references before recovery.
Missing, corrupt or incomplete files are retained and fail opening explicitly.
This avoids replacing existing files, which produced an actual NTFS access-denied
failure in the first combined audit; no delete/retry fallback hides that failure.

PREPARED evidence becomes CONFIRMED only on RETURNED; interrupted or unconfirmed
outcomes enter REVIEW. Reopening converts unfinished admissions to REVIEW/MISSING.
An explicit operator UUID and explanation are retained with acceptance/rejection.
The resolution method is a storage API; an authenticated operator command remains
to be implemented. Confirmed/applied totals use checked addition. A task adapter
must apply the absolute confirmed total through the native task owner before
marking evidence APPLIED, making replay independent of increment-on-retry.

## Validation and limits

The isolated audit uses actual result-slot clicks, native ingredient-remainder
failures, subsequent successful takes, a failing event consumer, partial inventory
drops, real KubeJS reload and the selected FTB stack. Journal reopening and duplicate
admission preserve totals, and native item codecs read back retained components.
Unit coverage includes rollback fencing, exclusive ownership, corrupt/incomplete
records, revision gaps, changed admission history and checked overflow.

This adds the reusable journal and outcome contract. Production task binding,
absolute FTB CustomTask projection, durable reward claims, authenticated resolution
commands, machine attribution and actual process-kill/world-rollback acceptance
remain open. The audit's adapter is fixture-owned; it does not install an automatic
production consumer. No inventory/world/journal atomic transaction or power-loss
persistence guarantee is claimed. CRAFT-04/05 are still incomplete.

Exact final validation counts and hashes are retained in
`artifacts/quest-evidence-journal-validation-2026-10-18.json`.
