# Staged, strict shop loading

Shop reload now decodes into fresh candidate maps before replacing live NPCs or shops.
An unknown nested action, failed required child, malformed collection/object shape,
unknown enum or incompatible value type aborts the candidate. Current shop identities
remain available, and saving is blocked after a decode failure until a successful source
read and retry. Successful publication replaces both maps on the existing StoredShops
object, removing shops deleted from the source. It preserves the outer object held by
ShopTrait instances.

Prepared loads are bound to a source generation. Re-reading or serializing the source,
or publishing another prepared load, invalidates older candidates. Publication does not
repeat decoding. This is not a concurrency transaction across arbitrary external edits
to public shop maps; ordinary reload runs on the server thread under Citizens' existing
data-operation guard.

PersistenceLoader.loadStrict is opt-in. It propagates nested failures through reflective
and registry delegates rather than silently skipping a failed map/list entry. The strict
context is restored after failure; ordinary NPC/trait persistence retains its existing
lenient behavior. Map values use their declared value type rather than their key type,
preserving quoted numeric purchase counts. Legitimate scalar roots declared by a
persisted scalar field remain supported, including the legacy empty-shop name format.
This does not impose a complete closed schema on unknown extra fields or replace
custom delegates' own domain validation.

## Validation

The [native fixture](../tools/strict-shop-runtime-audit/README.md) passes 48 checks on
NeoForge 21.1.233 and 21.1.248, including failure before NPC replacement, preservation
of live shop maps and source text, overwrite rejection and recovery. Unit tests cover
unknown nested actions, malformed collections/enums, fresh-map replacement, stale
preparations, literal dotted names, repeated empty-shop round trips, quoted purchase
counts and restoration of lenient loading after a strict failure.

All 316 ordinary tests and 100 Interactions tests pass without failures, errors or skips,
followed by a successful default-version clean test/build. Release archives exclude
fixture classes. All 78 Interactions entry names/contents, including its versioned
manifest, match the preceding release; the rebuilt ZIP container hash differs. The
original Citizens save retains SHA-256
`cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Early repeated-start testing exposed overly broad rejection of scalar empty shops;
that compatibility regression was fixed and receives a dedicated test. An intermediate
test also attempted to mutate an immutable fixture map; its data setup was corrected.
Neither intermediate run is counted as successful validation. Local evidence is recorded
in `artifacts/strict-shop-validation-summary.json`.

Shop inventory viewers/editors and trader menus still need explicit invalidation or
closure when successful publication detaches their old objects. Full transactionality
across configuration, NPC provider state, shops and templates also remains unfinished.
These limitations are not accepted as complete Citizens behavior.

Interactions remains an independent NeoForge JAR, with dependency Interactions → Citizens.
