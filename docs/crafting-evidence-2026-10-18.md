# Native crafting result observations

Citizens publishes `CraftingResultEvent` on the NeoForge game event bus for
supported native crafting-table and player-grid result takes. Consumers receive
the current actor and an immutable `CraftingEvidence.Observation`: UUID, actor UUID,
dimension, server tick, menu identity, grid dimensions, pre-consumption input stacks,
complete actual output batch and the current recipe ID/revision/definition snapshot.
Stack accessors return defensive copies. One native event instance publishes at most
one observation, including callback reentry. Separate output batches have separate
observation IDs.

Attribution uses the result container's public `RecipeCraftingHolder.getRecipeUsed`,
requires that exact holder to belong to the current recipe manager, verifies the
event's grid belongs to the current native menu and checks native recipe matching
and assembled output. It does not guess a recipe by searching for an equal output.
The active online server-thread actor is required. Only the supported static recipe
capabilities described in [recipe discovery](crafting-recipe-discovery-2026-10-18.md)
are accepted. Unknown menus, unsupported/dynamic recipes, stale holders, mismatched
grids/results and unavailable actor sessions emit no attributed observation.

The event runs during vanilla's result-taking hook **before ingredient consumption**.
For shift crafting it reports the full native output batch, including output that
vanilla may subsequently drop when inventory space is insufficient. It does not
mean that all output fits in inventory. Callback exceptions are contained so they
cannot abort the remaining native ingredient-consumption path after output moved.
The event is not cancellable; it grants no items and changes no task state.

The UUID identifies this in-process observation. A durable consumer must persist it
together with admitted task generation and beneficiary identity before applying
progress, and deduplicate using that retained identity. These events cannot be
replayed from Minecraft after a crash. This source adapter does not journal,
establish inventory/world persistence, infer task generations or automatically
advance FTB tasks. The pre-consumption crash window requires explicit reconciliation
in the task adapter. CRAFT-04/05 remain open until that durable consumer is verified.

FTB's existing item-crafting task observes actual item/count events and is independent
of this recipe-specific adapter. Its native item count alone does not bind a task to
a particular recipe ID. Moving, granting or buying the same item is not a crafting
observation. Machine processing and unattributable automation require separate
provider contracts under CRAFT-03/04.

The isolated combined audit exercises real native result clicks and FTB's native
crafting hook rather than directly fabricating a crafting event. The FTB task is
loaded from fixture-owned native quest SNBT at startup, and the crafting actor is an
actual `ServerPlayer`: Architectury intentionally classifies every subclass as fake.
No provider cache is cleared to make the native detector accept programmatic edits.
Pickup, repeated shift craft, player-grid craft, the actual reloaded KubeJS recipe,
full/partial inventories, duplicate
bridge entry, defensive snapshots and consumer failure are covered. Validation evidence
and release hashes are recorded in
`artifacts/crafting-evidence-validation-2026-10-18.json`.

Final validation: 93 combined native checks on NeoForge 21.1.248, 127 separate
Economy/camera/provider-absence checks and 730 ordinary tests on the 21.1.233
baseline, with zero unit failures/errors/skips. Build and separate-archive checks
pass; runtime audit classes are absent from release binaries and sources.
