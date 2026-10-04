# Configuration reads and shop preservation

Settings now checks the storage read result and stages every setting value before
publishing it. Defaults and migrations must save successfully before live values,
duration caches and messaging settings change. A failed read or migration/default write
leaves live values intact. Explicit configuration saving is blocked after a failed load
until the source is successfully loaded again, preventing a later save from overwriting
the unreadable file with stale data. This preserves existing coercion/default behavior;
it does not introduce complete semantic validation of every setting value.

Ordinary reload reads shop storage before changing NPC runtime ownership. An unreadable
shop source aborts reload, preserving the current NPCs and live shop maps. StoredShops
rejects load/save operations until its source has been read successfully and blocks
writes after a failed read until recovery. Shop serialization uses DataKey.writeAtomically,
so a serialization failure cannot publish a partially modified storage document.

Runtime NPC retirement now calls the additive `Trait.onRetire()` hook. Its default
delegates to the existing `onRemove(DESTROYED)` cleanup for compatibility. Traits owning
separate persistent records can override it to preserve those records while releasing
runtime resources. ShopTrait does so: replacing an old registry, retiring a failed
candidate, or shutting down no longer deletes the NPC's shop record. Actual NPC
destruction and explicit ShopTrait removal continue to delete that shop.

## Validation and limits

The [isolated native fixture](../tools/shop-retirement-runtime-audit/README.md) passes
44 checks on NeoForge 21.1.233 and 21.1.248. It includes malformed configuration/shop
reads, unchanged source files and runtime NPCs, overwrite rejection, repaired-source
recovery, failed candidate/shop retention, successful store replacement, and actual
deletion/removal semantics. With the original `47c52ca` ShopTrait, it fails
`failed_candidate_retirement_preserves_live_shop` and `store_replacement_preserves_shop`.

Two new Settings tests cover malformed reads and injected migration/default-save
failure, retaining all setting values, duration caches and messaging until successful
retry. A StoredShops test verifies partial serialization never enters the storage
document and that retry can publish a complete replacement. Existing shutdown regression
retains its 59 checks, including legacy trait cleanup through the new retirement hook.

All 310 ordinary tests and 100 Interactions tests pass with zero failures/errors/skips,
followed by a successful default-version clean test/build. Release archives exclude
fixture classes. All 78 Interactions entry names/contents, including its versioned
manifest, match the preceding release; ZIP container bytes differ. Archive ownership
remains separate. The original Citizens save retains SHA-256
`cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Local logs and archive/source hashes are summarized in
`artifacts/config-shop-validation-summary.json`. An initial source-guard compile failure
caused by local-variable shadowing was corrected before the successful runs; it is not
the old-behavior reproduction.

This does **not** make the complete reload transactional. The following remain concrete
work rather than accepted behavior:

- Nested shop decoding can silently omit failed items/actions; checking only the top-level
  PersistenceLoader result or parsing YAML successfully is insufficient.
- Existing map decoding can retain shops removed from the source file.
- Shop inventory viewers/editors and trader menus hold old shop/storage references.
  Editor.leaveAll does not close these menus; successful replacement needs explicit
  invalidation or closure before publishing new shop state.
- Configuration, NPC provider state, shop data and templates are not one atomic commit.
  Arbitrary external provider effects cannot be rolled back through NPC retirement alone.

Interactions remains its own NeoForge mod/JAR with dependency Interactions → Citizens.
Its implementation/resources have not been moved into Citizens.
