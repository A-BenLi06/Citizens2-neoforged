# Create basin mixing and compacting evidence

Follow-up 2026-10-23, UTC+08:00: [multi-item/fluid mixing recovery](create-basin-recovery-2026-10-23.md)
passes six actual PREPARED/CONFIRMED/APPLIED kills across both native targets,
with exact saved storage, retained payload and quest-credit fences. Other basin
processes and storage-interior kill points remain separate acceptance gates.

Date: 2026-10-19, UTC+08:00. Advances CRAFT-03–05 and ADAPT-05; the full port
and integrated migration remain unfinished.

## Native processing boundary

The pinned Create 6.0.10-mc1.21.1-performance-v6 uses BasinRecipe.apply first to
simulate input availability and output capacity. That pass rolls the actual item
results and evaluates native remainder callbacks. It then consumes each selected
item, shrinks the actual fluid stacks and stores those same outputs. Matching calls
use the simulation path and do not constitute completed craft evidence.

The optional adapter scopes BasinOperatingBlockEntity.applyBasinRecipe to its live
mechanical mixer/press. Only the exact basin two blocks below, the actual selected
current registry recipe object, an authenticated assignment and an online non-fake
beneficiary can admit evidence. Mixing and compacting use their own native process
types. No table event, purchased item or unattributed basin API call substitutes
for completion.

After the native capacity simulation accepts outputs, the adapter copies the
selected per-slot item quantities, exact component-bearing fluid plan, actual roll,
native remainders and complete prior basin storage. Durable PREPARED admission occurs
before actual consumption. It observes each returned native item extraction and
fluid shrink without selecting ingredients again or rolling additional results.
Confirmation requires the plan to equal actual consumed items/fluids, a successful
native storage/application return, live unchanged machine/basin and an exact
component-sensitive storage multiset. Admission-time storage changes fence the
observation into review. Native errors retain their original propagation.

The read-only basin bridge includes public item/fluid capabilities and both pinned
spoutputBuffer/spoutputFluidBuffer queues. Accepted horizontal outputs remain in
the producing basin's native pending queues before later transfer; they are verified
there without prematurely crediting from a computation. Both direct basin storage
and subsequent native receiver transfer are exercised. No save method, nearby
entity search or synthetic inventory write supplies completion evidence.

## Multi-input payload and fluid progress

Observation adds an optional BasinData extension with basin position, all selected
consumed item stacks, exact consumed fluids, prior fluids and native fluid outputs.
All accessors return copied item/fluid values. Existing observation constructors
and non-basin payloads remain compatible; transport/world fields keep their existing
semantics. consumedInput may be empty for a recipe with only fluid inputs and uses
the optional native stack codec. beforeOutput retains the complete pre-operation
basin item storage, while the extension carries its fluid equivalent.

FTB evidence continues to count exact actual primary item outputs when a recipe has
an item result. For a supported fluid-only basin recipe, its first native fluid
result defines primary identity/components and task units are actual millibuckets.
Secondary fluids, random item outputs and remainders remain verification data and
do not add primary units. Binding remains immutable and explicitly migratable;
duplicate observations, party/repeat generations and absolute journal projection
reuse the existing contracts. No fluid consumption is repeated by reconciliation.

## Reproduced provider remainder defect

Native acceptance reproduced missing basin remainders. DummyCraftingContainer
builds an expanded list containing one stack per selected consumed item, but its
superclass was constructed with a 0-by-0 grid. It inherits getWidth/getHeight/getItems
from that empty grid. Minecraft's default asCraftInput consequently supplied an
empty RecipeInput to native getRemainingItems, so no callbacks ran and the expected
remainder buckets disappeared.

CreateDummyCraftingInputMixin projects the provider-owned expanded inventory as a
one-row grid with defensive item copies (or 0-by-0 when empty). The fix corrects
the input supplied to Minecraft; it neither invents remainders nor replaces their
callbacks. The native fixture proves two selected inputs reach CraftingInput,
two actual buckets are stored, remaining input is retained and only paper counts
for task progress. Simulation remainder exceptions preserve inputs without
emitting completion evidence. This optional correction is pinned to the inspected
provider class and has no effect when Create is absent.

## Validation

The opt-in BasinMachineRuntimeAudit uses a real motor/cogwheel-driven mixer and
motor-driven compacting press. Native ticking supplies matching, animation,
consumption and storage; direct match calls only test simulation boundaries.
Twenty-three basin checks cover:

- Powered mixer/press, unassigned/offline attribution and native reconnect.
- Multiple item quantities, fluid consumption, retained inputs and stored outputs.
- Actual random and component-bearing outputs; defensive/durable snapshots.
- Duplicate outcomes and reopened journal absolute item/fluid progress.
- Missing fluid and heat, plus a real heated burner block state.
- Fluid-only and pure-fluid-input/output recipes, using exact millibucket units.
- Admission-time output-capacity/input-fluid mutation requires review without credit.
- Correct native dummy crafting input, stored remainders and simulation failure.
- Native spout pending outputs and later transfer to a real receiver basin.

Both NeoForge targets also run 54 existing Create, 23 batch, 16 world-press and
21 transport-press checks. Final logs, package/source/provider hashes, absent-Create
regression and ordinary build counts are recorded in
create-basin-validation-2026-10-19.json.

Retained fixture failures informed actual fixes: clearContent leaves fluids intact,
so synthetic tank reset is explicit; introducing a recipe after the native recipe
trie was cached did not exercise the desired case, so all fixture recipes are
registered before the first native query; the remainder failure above required
the actual container correction. Production data/processes were untouched.

## Remaining gates

Fan processes, deploying, filling/emptying, sequenced assembly and other required
machine evidence remain open. Basin/transport-specific process-kill points,
inventory mutations, saved-world/FTB/journal rollback and financial-backup recovery
need broader validation. Native pending queues are accepted storage, not an atomic
transaction across independently saved world, quest and evidence files.

The full party/repeat/offline/reconnect/reward matrix, logging operations, legacy
quest/item provenance, supported Paradigm inbound evaluation and physical VR/non-VR
integrated release acceptance remain required in the adopted goals. Citizens and
Interactions stay separate JARs. No production update or restart was performed.
