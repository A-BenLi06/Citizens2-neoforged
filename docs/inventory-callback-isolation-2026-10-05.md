# Inventory callback isolation

InventoryMultiplexer previously passed references to real container ItemStacks into its
callback. Shrinking a stack and then throwing changed the real inventory despite the
failed transaction. NPCShopStorage had the same aliasing problem. Both now give callbacks
independent ItemStack snapshots and publish detached copies only after callback success.
Keeping the callback array cannot later mutate published inventory or stock.

The multiplexer caches a detached read snapshot. refresh replaces it only after reading
all sources, and successful publication reads back actual container state so clamping
does not leave the cached count larger than the real count. Its source collection is
fixed and changed source sizes are rejected before publication. Nested transactions and
refresh during publication are rejected; private-stock replacement is also rejected
while that stock callback is running. Guards release after failure so retry remains usable.

These guarantees apply to the callback's supplied snapshot. They do not sandbox direct
external writes through other references. Container setters/change listeners still publish
sequentially; arbitrary failures or external side effects during that publication phase
are not a general cross-container rollback protocol. Native ItemStack.copy semantics are
used rather than inventing serialization rules for third-party components.

## Evidence

Five targeted tests cover callback failure/retry, retained aliases, detached reads,
reentrant writes, private stock and container clamping. The original implementation fails
four of five tests. Native purchase-completion regression passes 31 checks on both
NeoForge 21.1.233 and 21.1.248, including merchant refunds, notifications and full-inventory
escrow. Evidence is summarized in `artifacts/inventory-isolation-validation-summary.json`.

Command rollback regression passes 26 checks. All 325 ordinary tests and 100 Interactions
tests pass without failures/errors/skips, followed by a successful clean test/build.
Release archives exclude fixtures. All 78 Interactions entry names/contents remain
unchanged, including the versioned manifest; rebuilt ZIP container hashes differ.
The original Citizens save retains SHA-256
`cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Interactions remains a separate NeoForge JAR with dependency Interactions → Citizens.
Physical-client/provider acceptance and full reload transactionality remain separate work.
