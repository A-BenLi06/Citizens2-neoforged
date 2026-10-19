# Native Create sequenced assembly evidence

Date: 2026-10-19, UTC+08:00. Advances CRAFT-03–05 and ADAPT-05.
Full port, recovery and integrated acceptance remain unfinished.

## Recipe and attribution contract

The optional adapter reads the pinned Create 6.0.10-mc1.21.1-performance-v6 public
sequenced assembly definition. Assembly has a separate typed snapshot: native initial
ingredient, component-sensitive transitional item, ordered processing steps, loops
and relative final-result weights. Weights above one are retained; the pinned native
ProcessingOutput codec requires positive floats. The adapter does not normalize
native data, select a result or consume RNG. NPC recipe guidance reports the parent
loop/step counts, relative probabilities and each step's workstation. There is no
single assembly workstation. Output lookup includes every weighted parent result;
input matching uses the native initial-ingredient predicate. Canonical revisions
include native definitions and resolved ingredient/item/fluid components.

Discovery and completed evidence are distinct capabilities. A parent can be bound
only when every step has a supported native processing adapter. Custom or unavailable
step adapters cannot gain completion support merely because assembly is discoverable.
Existing table/ordinary processing snapshots and observation constructors remain
compatible; the serialized ordinary machine payload is unchanged.

Only a live supported machine, explicitly assigned to an online non-fake beneficiary,
can acquire final assembly evidence. The optional advance wrapper retains the actual
native final result for each input roll while that machine owns the operation. It
checks the unique registered parent object/ID, current canonical revision, native
assembly component and final step, actual embedded step recipe and machine process.
Intermediate steps and unrelated native utility calls produce no completed evidence.
Embedded step recipes are accepted only through this observed native parent advance;
an unregistered processing recipe alone cannot acquire attribution.

The pack attribution rule credits the beneficiary assigned to the final machine.
Create's native intermediate component establishes the sequence position; this is
not a durable history of earlier machines, ingredient owners or earlier beneficiaries.
Inventory transfer or picking up the final item cannot synthesize a completion event.

## Completion and recovery boundary

The final machine retains its existing storage verification. World pressing verifies
the original input entity and exact accepted output/remainder entities. Depot and belt
processing verify complete held/buffer/pending inventory deltas without invoking
mutating queue serialization. Saw output verifies the actual complete processing
inventory. Final deployment verifies native tool handling; final filling verifies
the actual tank consumption and item storage.

The parent object, unique registration and revision are rechecked after storage.
Replacing it during admission requires review even when the new definition has the
same revision. Mutated inputs, tools, fluids, rejected storage and changed recipes
stay UNCONFIRMED; native exceptions after admission propagate and become INTERRUPTED.
Exceptions before admission cannot invent completed evidence. Native remainders are
called only by Create and retain their actual quantities/components.

Quest progress uses the observed final assembly results matching the parent primary
output, with complete components. Incidental step outputs are still required for
storage confirmation but cannot supply assembly progress, even when they match the
parent primary item. Weighted scrap and remainders cannot become primary progress.
The existing durable team/cycle binding and absolute projection prevent duplicates;
reconciliation never reruns a recipe or remainder callback.

## Native validation

AssemblyRuntimeAudit runs powered native world/depot/belt pressing, deploying, filling
and cutting. Forty-one assembly checks cover:

- Separate discovery, relative weights/components, secondary output lookup, native
  initial input matching and defensive snapshots.
- Intermediate exclusion, unassigned/offline beneficiaries, utility exclusion,
  actual bulk/final rolls, native nonbulk retained intermediates and weighted scrap.
- Durable encoding and duplicate notifications, rejected/changed world/depot inputs,
  connected belt pending storage and completion of the native press cycle.
- Actual press → deployer → spout → saw sequence, consuming the native tool/fluid
  along the way and awarding only the final stored assembly output.
- Incidental matching secondary outputs: the native saw stores six paper, while the
  parent final result and quest progress are two paper.
- Final deploying/filling confirmation and admission-time tool/fluid mutations.
- Current parent replacement, revision changes, same-revision identity replacement
  during admission and ambiguous native parent registration.
- Exact distinct native remainders, rejected remainder storage, native exceptions
  before/after admission and reopened absolute journal progress.

Both NeoForge 21.1.233 and 21.1.248 run these alongside 222 existing Create checks.
Create absence passes 141 checks; clean ordinary builds pass 767 tests on each
target, and the separate Interactions suite passes 100 tests. Independent retained Citizens/Interactions archives
and exact source/provider/log/package hashes are recorded in
[create-assembly-validation-2026-10-19.json](create-assembly-validation-2026-10-19.json).

Failed fixture runs remain local. The last fixture failure came from starting a
recipe-replacement world cycle before the preceding belt press animation finished.
The fixture now waits for the actual native cycle to finish; no machine timer,
matching, RNG or storage behavior was changed to satisfy it.

Other required unsupported machine types, machine-specific hard-kill/inventory
rollback including assembly, wider party/repeat/offline/reconnect and financial
backup matrices, logging operations, legacy provenance, supported Paradigm inbound
formatting and physical VR/non-VR integrated migration remain open. Accepted native
pending storage is not an atomic saved-world/FTB/journal transaction. No production
deployment occurred.
