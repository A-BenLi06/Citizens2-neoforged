# Private shop storage ownership

Shop sessions now belong to both their shop definition and the concrete NPCShopStorage
they use. A shared definition can remain active while one NPC's private stock is retired.
ShopTrait retirement closes only sessions using that trait's stock; unrelated named
registries, same-UUID NPCs and the shared shop's own stock remain independent.
Chest/editor/trader openings, retained purchase callbacks and stock transactions reject
retired storage. Its saved balance and inventory remain readable for persistence.

The shared ShopSessionOwner helper holds weak session targets, transaction counts and
retirement state. Temporary suspension closes editors before the old NPC store is saved
and blocks new private sessions during the replacement. Resume tokens are scoped to
their exact suspension and cannot reactivate a later suspension or retired owner.

Default-store replacement checks affected private stocks before invoking the provider.
Only after candidate loading succeeds does it suspend old private sessions and save the
old registry, including stock edits committed by closing its inventory editor. Successful
replacement then retires old stocks. Failed provider loading leaves old views open;
failed saving leaves their edited stock in memory and resumes admission so users can
reopen them. It does not reopen already closed screens automatically. Old edits are saved
to the old provider, not copied into the replacement provider's independent records.

ShopTrait removal attempts private-stock cleanup and definition deletion independently,
aggregating failures. Shop definition retirement also retires its own stock. Normal
stock-editor closure can still write its final inventory before the old stock is saved.

## Evidence and limits

The [native fixture](../tools/private-shop-runtime-audit/README.md) passes 28 checks on
NeoForge 21.1.233 and 21.1.248. It exercises six admitted mock players, failed candidates,
failed old-store saves, transaction reentry, buffered inventory edits, shared definitions,
same-UUID named-registry NPCs, independent replacement data and stale callbacks.
Two owner tests cover suspension-token identity and failure-isolated stock retirement.

Regression passes 78 default-store replacement checks and 33 shop-session checks.
All 320 ordinary tests and 100 Interactions tests pass without failures/errors/skips,
followed by a successful default-version clean test/build. Release archives exclude
fixtures. All 78 Interactions entry names/contents match the preceding release, including
its versioned manifest; rebuilt ZIP container hashes differ. The original Citizens save
retains SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Evidence is consolidated in `artifacts/private-shop-validation-summary.json`. One native
run reports an unrelated authlib public-key fetch timeout after its checks; this does
not establish external authentication/network acceptance. Physical clients, arbitrary
custom-provider side effects, nested destructive command actions during transactions,
and a transaction across every reload participant remain separate acceptance.

Interactions remains a separate NeoForge JAR with dependency Interactions → Citizens.
