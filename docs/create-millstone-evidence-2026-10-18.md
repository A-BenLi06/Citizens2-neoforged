# Create millstone evidence and FTB projection

The selected Create `6.0.10-mc1.21.1-performance-v6` now supplies an optional native
millstone evidence source. This is a supported milling subset, distinct from the
eleven processing types available for recipe guidance. Crushing, mixing, pressing,
cutting, fan processing, deploying, filling, emptying, mechanical grids and assembly
do not yet provide attributed task evidence through this adapter.

The pack rule is explicit assignment to an online beneficiary. Create's advancement
owner is temporary and can clear once advancements are awarded; it is not used as a
durable machine owner. An authenticated operator with `citizens.admin` assigns an
existing loaded millstone in their current dimension:

```
/citizens craftevidence assignmachine <beneficiary UUID> <x> <y> <z>
/citizens craftevidence clearmachine <x> <y> <z>
/citizens craftevidence bind <FTB CustomTask ID> <recipe ID>
```

Assignment retains a fresh machine identity, beneficiary/operator UUIDs and timestamp
in a serialized NeoForge block-entity attachment. Existing assignments require an
explicit clear before replacement. Block destruction/replacement loses assignment;
movement/copying by other mods must retain the attachment to retain attribution.
This confers no world access/protection authority. Unassigned, disconnected or fake
beneficiaries receive no task evidence. Offline automation still runs normally and
does not receive catch-up credit. The beneficiary's native current FTB team,
visibility, prerequisites and repeat cycle are admitted when actual outputs roll.

The optional mixin scopes Create's existing `process` call. It observes the exact
native remainder once, pre-shrink input, actual current recipe instance and actual
random result list after consumption, before insertion. It never selects an alternate
recipe, rolls again, replaces AI/kinetics or changes provider inventory behavior.
Create can retain `lastRecipe` across reload: evidence is omitted if that object no
longer belongs to exactly one current registry holder, even when the ID is reused.
No cached recipe is relabeled as a new revision.

`MachineResultEvent` exposes an immutable observation with machine identity, operator/
beneficiary, location, native recipe/revision, full input/remainder/pre-output
components and actual rolled outputs. `MachineOutcomeEvent.RETURNED` requires the
native call to return and the complete component-sensitive input/output/remainder
inventory delta to match. Interrupted calls or unmatched deltas retain review
evidence; listener failures cannot abort native processing. Full inventory's native
stop emits no observation. Partial capacity in this provider can discard remainders
from `insertItemStacked`; such output loss never confirms task progress.

FTB bindings count only the actual component-sensitive items equal to the recipe's
first display output. Multiple rolls of that item add their actual quantities;
secondary products and container remainders do not add primary-output units. A roll
with zero matching output adds no task evidence. Different output targets need an
explicit future binding contract; output possession, purchase and extraction are
not completion signals. Existing immutable binding epoch/revision/maximum policy,
absolute projection, review and reconciliation apply. One observation/task identity
is deduplicated across native repeat generations and beneficiary team changes.
Replaying an old notification cannot create a new generation's progress.

Durability limits remain explicit. Journal PREPARED begins after native consumption
and random rolling. A process kill between consumption/rolling and durable admission
has no reconstructible provider observation. A kill after PREPARED and before outcome
becomes REVIEW on reopen. RETURNED verifies in-memory native processing, not atomic
world/player/FTB persistence. A saved journal plus rolled-back world or FTB progress
requires explicit review; historical APPLIED evidence never silently repairs rollback.
Monetary reward generations, actual process-kill acceptance, offline attribution and
other machine process evidence remain open under CRAFT-04/05 and ADAPT-11.

Validation and exact provider/release/log hashes are recorded in
`artifacts/create-machine-evidence-validation-2026-10-18.json`. The isolated fixture
uses the actual Create JAR, an actual connected ServerPlayer, native FTB team/reward
claims, creative motor and millstone with native rotation propagation/ticking. No
machine-processing method is invoked by reflection. Runtime probes remain excluded
from standard Citizens/Interactions binaries and sources.

The fixture passes 54 checks on each NeoForge 21.1.233 and 21.1.248, including
the 30 recipe checks, native powered processing, capacity loss/review, callback
exceptions, native remainder called once, serialized assignment, journal reopen,
reward-reset and team-change replay fences, and offline beneficiary exclusion.
The separate absent-Create FTB/Paradigm/KubeJS suite passes 137 checks. The ordinary
21.1.233 build passes 746 tests with zero failures/errors/skips. Release inspection
confirms no probes/provider classes or cross-mod packaging leaks.
