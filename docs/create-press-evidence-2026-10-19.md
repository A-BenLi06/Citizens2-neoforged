# Native Create world pressing evidence

2026-10-19, UTC+08:00. Extends [batch machine evidence](create-batch-evidence-2026-10-19.md)
for the selected Create 6.0.10-mc1.21.1-performance-v6.

Operators can assign a loaded mechanical press with the existing
`/citizens craftevidence assignmachine <beneficiary UUID> <x> <y> <z>` command
and bind an ordinary registered `create:pressing` recipe to an FTB CustomTask.
The assignment is persistent attribution to an online beneficiary. It grants no
world access authority. Native bulk-pressing configuration remains authoritative.

The optional adapter wraps the press's real `tryProcessInWorld` operation. Native
simulation is unchanged and emits no evidence. Before the provider computes an
actual result, the adapter preserves the input entity UUID, complete input stack
and native processed quantity. RecipeApplier's actual random roll outputs and
remainder callbacks are copied before merging. The adapter does not reselect a
recipe, roll again, invoke a remainder callback again or replace native output.

Durable PREPARED admission occurs after native result computation and before
world storage. The current recipe object must belong to one current registry
holder. Transient sequenced-assembly step recipes are not relabeled as registered
pressing recipes. FTB admission freezes the current beneficiary/team/repeat cycle
through the existing evidence journal. Primary output units exclude remainders.

Confirmation verifies the original entity's actual retained/replaced stack and
every native output entity insertion by identity. It compares the full
component-sensitive output/remainder/input multiset. Canceled insertion, removed
outputs or native failure cannot confirm progress. Nearby dropped items never
supply confirmation. Interrupted observations remain reviewable; exceptions
before admission keep native behavior without inventing a completed observation.

Bulk mode consumes the native stack; single mode consumes one item and retains
the remaining stack. Duplicate result/outcome notification and journal reopening
preserve absolute progress. Existing observation constructors and non-world
payloads stay compatible; world evidence adds `inputEntityId` only when present.

The native fixture exercises a powered motor/press cycle, bulk and single mode,
full output components and actual random results, simulation, native remainder
counts, rejected remainder-entity insertion, remainder exceptions, persistent
assignment and journal reopening. An unrelated public RecipeApplier call produces
no machine evidence. Sixteen press checks supplement the existing 54 Create and
23 batch checks on each supported NeoForge target. The standard build excludes
all probes and foreign provider implementation classes.

This follow-up implements world-item pressing. Belt pressing, basin compacting,
mixing, fan processing, deploying, filling/emptying and sequenced assembly still
require their real storage/consumption/attribution contracts under CRAFT-03–05.
World/FTB/evidence stores remain independently saved; the earlier saw process-kill
checks do not prove all press kill points. Press-specific hard termination,
contraption copying, offline/reconnect, financial-backup recovery and physical
client acceptance remain required before full release completion.
