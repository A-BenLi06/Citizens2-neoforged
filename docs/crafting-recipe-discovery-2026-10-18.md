# Native recipe discovery

`CraftingRecipes` reads the active server recipe manager after datapack/provider
loading. It retains no recipe-manager or result cache. Its public server-thread API
provides:

- `find(server, recipeId)` for an explicit registry ID; absence stays empty.
- `byType(server, typeId, limit)` for a registered recipe type in stable ID order;
  unknown type IDs fail explicitly and the limit is 1–100.
- `byOutput(server, stack, matchComponents, limit)` for stable-ID-ordered static
  output lookup with a complete native component filter and limit 1–100. Stack count
  is not a search filter; snapshots contain the actual output batch quantity.
- `current(server, recipeId, revision)` for checking a captured view.
- `matchesInput(server, recipeId, revision, slot, stack)` for the current provider's
  ingredient predicate. It rejects stale or unsupported views.

Snapshots include recipe/type/serializer IDs, native codec JSON, support kind,
workstation, shaped dimensions, ordered ingredient slots and native output. Inputs
retain serialized definitions (including tags/custom ingredients), custom flags and
defensive candidate stacks. Candidates are display alternatives, not replacements
for native matching. Matching one slot does not prove whole-grid processing,
remainders or actual output. Output/candidate stacks and lists are defensive.

SHA-256 revisions cover canonical native recipe JSON, resolved ingredient candidates
and the complete output. Result/tag expansion changes invalidate captured views even
with the same ID. Missing providers, incomplete supported ingredients and native
serialization errors fail explicitly; no substitute item is created. Static output
search cannot infer dynamic callback results.

Guidance supports native shaped/shapeless implementations that fit the vanilla
crafting table. The selected KubeJS 2101.7.2-build.377 public wrappers are supported
only when their ingredient-action list and result-modifier key are empty. Their full
native definition remains available, including provider-specific shape options.
Special recipes, callback-driven KubeJS recipes, unknown subclasses, cooking and
other machine types return `UNSUPPORTED`. No guessed workstation, fluid, energy or
probability fields are supplied for them. Machine adapters and craft evidence remain
separate work under CRAFT-03–05.

NPC commands use `/citizens recipe <recipe ID>`. Separate Interactions adds
`recipe: <recipe ID>`. Dialogue preflight resolves supported live definitions before
other actions consume payment; execution reacquires them. The command can describe
unsupported types explicitly. Guidance grants no output, consumes no ingredients
and advances no FTB task or reward. Authored task eligibility remains the owner's
responsibility.

The guarded quest fixture uses selected KubeJS/Rhino jars and a fixture-owned
script. It loads a three-paper recipe, edits that script to four paper, calls the
real asynchronous server resource reload, and waits across ticks before checking
the changed output and invalid old revision. It also covers component predicates,
tag identity/alternatives, output filtering, defensive snapshots, shaped empty
slots, unsupported cooking, native/packet NPC feedback, dialogue preflight,
replacement/removal and worker-thread rejection. Prepare with
`tools/prepare-quest-audit.ps1 -WithKubeJS`; preparation resets the owned script.
The optional recovered-item fixture remains independently selectable. Evidence is
in `artifacts/recipe-discovery-validation-2026-10-18.json`.

The combined NeoForge 21.1.248 fixture passes 62 checks, including rejection of a
real KubeJS wrapper with a nonempty result callback. The separate provider-absence/
Economy/camera suite passes 125 checks, including a missing recipe rejected before
dialogue payment.
The ordinary build passes 730 tests with zero failures/errors/skips; final archives
remain separate and contain no audit probes.

This supplies recipe lookup/guidance. The full attributed craft → FTB advancement →
durable currency reward acceptance flow remains open.
