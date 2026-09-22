# Unavailable saved item recovery

Implementation base: `5974d0e`. Target: Minecraft 1.21.1 / NeoForge 21.1.248.

## Retained definitions and native behavior

An empty slot and a saved item whose provider/metadata cannot be decoded previously both became null. Subsequent saves deleted the unavailable definition. Generic item lists also omitted unreadable entries, potentially weakening command/shop requirements. The preceding codec fix rejected partial stacks, but did not yet preserve their owning records.

`ItemStorage.readItem` now distinguishes available, empty and unavailable outcomes. `StoredItems` retains independent copies of the original unavailable definitions in their owners; it never creates a substitute native item. Native inventory, equipment and cosmetic equipment keep their original slot keys. Item-frame contents, item-provider suppliers, configured drops and shop display icons retain the same distinction. Native empty reads do not erase unresolved records. Reload retries the current providers, while explicit setters, clears or a successfully saved nonempty slot replacement supersede the retained definition. Direct APIs expose unresolved slot/state queries.

NPC snapshots/copies and owner saves preserve unavailable definitions, including unknown nested data and pending editable fields. Restored native items use the normal complete component codecs. An explicitly empty item-provider supplier is stored with Minecraft's optional empty-stack encoding, so reloading cannot reactivate the old metadata/default supplier. This does not change the separate legacy metadata-only default-supplier fallback.

Legacy metadata that cannot be fully decoded is unavailable instead of becoming a partially restored item. Both scalar `meta` and `meta.encoded-meta` reach a registered legacy reader. Reader failure never exposes its partially modified stack. Built-in decoding of Bukkit's serialized wrapper and older structured metadata remains unfinished; preserving the original definition allows that work or a returning provider to recover it later. Plain legacy item records continue to migrate.

## Costs, stock and editing

`StoredItemList` retains unavailable entries at their list positions. The reflective loader uses it for existing `List<ItemStack>` fields; the command-specific persister uses it for per-command costs. Whole item-list replacement encodes before altering the saved field. List removal/set/clear operations explicitly discard the affected deferred entries. Native stacks and unavailable data are independent when item actions and shop entries are cloned.

Commands reject unavailable global costs and matching-hand command-cost definitions before any initial payment. Delayed command costs recheck their current lists. Item actions reject unavailable costs/rewards, and limited shops reject item transactions against unavailable stock. Restoring a valid definition re-enables normal payment and execution. Unrelated command payment/scheduling semantics are unchanged; this is not a simulation or rollback guarantee for arbitrary future commands.

Cost, action, stock and drop editors show a translated unavailable-items explanation and preserve their data when opened/closed. A shop display editor keeps an unresolved display instead of deleting the shop entry; explicitly replacing/clearing it remains possible. The unavailable UI marker is only an editor control, never a gameplay reward. Client appearance remains unverified.

The public `loadItemStack` compatibility method still returns null when nothing loadable exists. External item-owning addons must use the explicit result/retention APIs to preserve unavailable data; this cannot be inferred safely from a bare null. Corrupt whole-list shapes and arbitrary third-party collection serializers are outside the verified valid item-list formats.

## Validation

The dedicated fixture loads the same saved data with actual providers absent, present and absent again across separate JVM runs. It verifies native provider accessors, each built-in owner, YAML persistence, live entity spawn/despawn, copy independence, explicit editing and empty-provider reload. The removal fixture verifies actual command payment/dispatch and editor preservation. Focused unit cases cover record/list integrity, native and legacy failures, pending edits and clone isolation.

Final sequential NeoForge 21.1.248 validation on 2026-09-22 passes 23 focused tests, 46/50/46 provider checks across absent/present/absent-again JVM restarts, 62 removal checks, 107 NPC assertions, 223 general runtime probes and 182 ordinary tests. Unit failures/errors/skips are zero; normal build succeeds. Release/source jars exclude runtime probes and include both recovery classes. Citizens jar SHA-256: `059ab873347b150125032994350d0c987b0efb5ac3ce7beb1f04a0bd8faf7e17`.

Successful logs, provider/source/jar hashes and fixture snapshots are recorded in `artifacts/item-recovery-validation-summary.json` and `walkthrough.md`. The original Citizens save hash remains unchanged; its six plain item records contain no serialized ItemMeta and cannot establish decoder acceptance. Unrelated dedicated suites retain their historical results. No production server files were changed or deployed. Full Citizens/Interactions/Sentinel parity and actual client/modpack/proxy acceptance remain open.
