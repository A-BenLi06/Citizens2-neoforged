# Exact shop item quantities

Item actions now use wide arithmetic for total requirements and available supply.
Maximum-repeat calculation tests the same cumulative allocation used by charging, so
duplicate requirements share their available supply correctly rather than assigning every
matching stack to only the first requirement. The result is capped to the existing int API.
Negative repeat requests fail; zero repeats remain no-ops.

Capacity checks and grants now use one placement algorithm. Existing partial stacks count
as capacity, oversized configured quantities split into legal stacks, and every repeat is
delivered. Container stack limits and placement policies are consulted. Quantities are
batched per configured template in last-configured-first priority; repeated types may be
packed together rather than preserving an alternating per-repeat slot arrangement.
An incomplete addition/removal throws before the callback snapshot can be published.

Limited stock plans actual merged occupancy instead of allocating items.size multiplied
by repeats. Exact capacity is accepted, and arithmetic cannot wrap a large request into
free capacity or a zero cost. Existing item-transfer compensation still restores the stock
step if player-side publication fails.

## Evidence and limits

Eight new unit tests pass; original code fails all eight. Existing item-action and
inventory-publication tests also pass. The [native fixture](../tools/item-quantity-runtime-audit/README.md)
passes 16 checks on each of NeoForge 21.1.233 and 21.1.248, including four-repeat chest and
merchant purchases, duplicate costs, limited stock, 100-item splitting and refund.
Evidence is summarized in `artifacts/item-quantity-validation-summary.json`.

Settlement and command rollback regressions pass 31 and 26 checks. All 340 ordinary tests
and 100 Interactions tests pass without failures/errors/skips, followed by a successful
clean test/build. Release archives exclude fixtures. All 78 Interactions entry names/contents
remain unchanged, including its versioned manifest; ZIP container hashes differ. The original
Citizens save retains SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

The arithmetic overflow tests use synthetic oversized source counts to verify that
calculation cannot wrap; they do not make those source stacks legal Minecraft inventory.
Native ItemStack component equality/capacity semantics are retained. Physical-client
behavior, arbitrary custom providers and full reload transactionality remain separate work.

Interactions remains an independent NeoForge JAR with dependency Interactions → Citizens.
