# Create processing recipe adapter

Selected by the user on 2026-10-18, UTC+08:00. The actual pack provider is
Create `6.0.10-mc1.21.1-performance-v6`, SHA-256
`5602a16796957cb5c7fb829c10c6b54bf9d228931c7680636710b6a60d92822e`.
No production files or server process were changed.

`CraftingRecipes.find/byType/byOutput` now discover Create's native milling,
crushing, mixing, compacting, pressing, cutting, splashing, haunting, deploying,
filling and emptying implementations. The optional public-method adapter supplies
a typed `CreateProcessingRecipes.Snapshot`: workstation, configured duration,
native heat condition, ordered sized fluid inputs and alternatives, every item
result with per-item probability, fluid results, and retained deploying-tool slots.
Item slots each require one item; repeated ingredients retain repeated slots.
Ingredient predicates, quantities, tags and complete components remain native.
Zero duration means unspecified; machine speed determines actual elapsed time.
No energy, shaft speed, basin/burner arrangement or item remainder is guessed.

`matchesFluidInput` reacquires the current native sized predicate and rejects
changed revisions, insufficient amounts and wrong fluids/components. Revision
hashes include native codec data, resolved item/fluid alternatives, output
components, probability, duration and heat. All stack/list snapshots are defensive.
Output search includes secondary probabilistic products without rolling any result.
The legacy `output()` is the provider's first display result; machine consumers
must use `processing().itemResults()/fluidResults()` for the complete result set.

NPC `/citizens recipe <id>` and separate Interactions' `recipe: <id>` share live
guidance. Messages are translatable and identify probability/configured duration.
Machine discovery does not authorize table evidence: `tableEvidenceSupported()`
is separate from guidance support, and existing craft-task binding rejects machine
recipes until a supported evidence source is installed. Assembly, mechanical
crafting grids, manual applications, polishing, generic basin recipes and unknown
subclasses remain explicitly unsupported. Provider absence adds no dependency.

Validation on NeoForge 21.1.248: 30 isolated Create checks with the actual provider,
including real wheat milling/brass mixing, eleven native types, native fluid and
component predicates, all-result lookup, immutable snapshots and actual KubeJS
resource reload. The existing absent-Create FTB/Paradigm/KubeJS fixture passes 137
checks. Its hidden placeholder fixture now loads a complete native SNBT task before
login; constructing an empty quest before its task cached completion for a later
player and was an invalid visibility precondition. Failed logs remain available.
The ordinary NeoForge 21.1.233 build passes 746 tests, zero failures/errors/skips.
Release binary/source archives remain separate and contain no runtime-audit probes.
Hashes and evidence: `artifacts/create-recipe-validation-2026-10-18.json`.

This completes the implemented Create processing guidance subset of CRAFT-03.
The subsequent [millstone evidence adapter](create-millstone-evidence-2026-10-18.md)
adds actual milling evidence and owned FTB projection. Other machine outcomes,
durable rewards, assembly and integrated crash/restart acceptance remain open.
