# Create filling and emptying evidence

Date: 2026-10-19, UTC+08:00. Advances CRAFT-03–05 and ADAPT-05. The full port
and integrated migration remain unfinished.

## Native operation boundary

The optional adapter observes the pinned Create 6.0.10-mc1.21.1-performance-v6
SpoutBlockEntity.whenItemHeld and ItemDrainBlockEntity.continueProcessing calls.
Only an explicitly assigned live machine, online non-fake beneficiary and the
actual uniquely registered current recipe object can produce admitted evidence.
Filling and emptying have distinct machine sources. Generic bucket/potion/capability
operations do not acquire a guessed registry identity. Transient sequenced recipes
remain excluded by the current-holder identity check; assembly evidence stays open.

Spout filling rolls results, consumes one item and shrinks its live fluid before
calling transport storage. PREPARED admission captures the actual roll before that
consumption. Confirmation verifies the original holder/input, the exact retained
item quantity, complete depot/belt storage and the final tank delta. Its storage
snapshot therefore accounts for the already consumed item instead of applying the
press's unchanged-input assumption. Depot overflow and belt pending queues retain
their existing exact storage checks. Reading a belt never flushes its queues.

The drain rolls during simulation and again during execution. Simulation rolls
never become evidence. The actual utility result is captured after it consumes a
copy of the held item, before the drain replaces its held stack or inserts fluid.
The original live held item must still be the same one-item input at admission.
Native insertion returns surplus input to the caller and holds one processable
item. Confirmation requires the actual item output and full tank delta to match
the complete native roll and returned fluid, after continueProcessing returns.

The pinned drain stores fluid from its simulated pair rather than its execution
pair and ignores the insertion return quantity. The adapter does not assume those
values match or that insertion succeeds: exact stored fluid is required. Any
mismatch, admission-time mutation, truncated output or interruption cannot confirm
progress. This change leaves native provider consumption/storage behavior intact.

Same-item filling can confirm when Create actually stores the result and consumes
the planned fluid. An empty random spout roll has no output or progress; it remains
unconfirmed even though native consumption occurred. Reconciliation never repeats
the native processing operation.

## Payload and progress

Observation adds optional FluidData with item-holder position, prior machine fluids,
consumed fluids and actual fluid outputs. Fluid quantities are millibuckets and
all accessors return defensive copies. Existing constructors and BasinData encoding
remain compatible. Item-output tasks count actual primary item units; fluid-only
recipes count actual primary fluid millibuckets. Secondary results and simulated
rolls cannot increase those units. Journal admission, duplicate fencing, binding
revision checks and absolute FTB projection use the existing contracts.

## Validation

FluidMachineRuntimeAudit performs 28 checks using native depot/belt spout ticks,
connected motor-powered belts, drain capability insertion and native drain ticks:

- Assigned/unassigned/offline machines, duplicate notifications and reopened journal.
- Single and final item/fluid quantities, retained input and stored component results.
- Defensive serialized fluid plans, actual random drain results and simulation exclusion.
- Empty random filling, same-item filling and insufficient spout fluid.
- Generic filling/emptying and unrelated utility calls without registered credit.
- Admission-time item/fluid/capacity changes requiring review without progress.
- Complete pending belt outputs and native single-item drain insertion surplus.
- Fluid-only progress, full drain waiting and current recipe replacement with old-binding rejection.

Both NeoForge 21.1.233 and 21.1.248 also run 137 existing Create checks. Separate
Create-absence and ordinary clean-build checks, retained package hashes and exact
logs are recorded in create-fluid-machine-validation-2026-10-19.json.

Fixture findings: Create's native chance codec rejects zero, so the empty-roll
fixture uses a valid low positive probability and a fixed synthetic RNG seed;
same-item filling has a valid fluid-consuming storage delta; draining a synthetic
tank alone does not clear a waiting held input, so reset clears both states.
These findings changed fixture expectations/reset logic, not provider behavior.

Fan/deployer and sequenced assembly, machine-specific hard-kill/inventory rollback,
wider party/repeat/offline/reconnect/financial backup acceptance, logging operations,
legacy provenance, supported Paradigm inbound formatting and physical VR/non-VR
integrated migration remain required. No production restart or deployment occurred.
