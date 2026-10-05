# Inventory publication recovery

InventoryMultiplexer now captures the actual pre-write source values and a fixed slot
plan before publication. If a setter or change notification throws, it restores every
attempted slot in reverse order, including a setter that mutated its slot before throwing.
Recovery continues after individual restoration failures, preserves the original exception,
and attaches diagnostics rather than reporting success.

It re-reads and compares the recovered stacks with the starting snapshot, detecting silent
restore clamping as well as thrown errors. When readback succeeds, its cached snapshot reflects observed inventory.
Incomplete recovery blocks further writes through that multiplexer until a successful
explicit refresh; refreshing acknowledges the current state and does not magically restore
missing items. A changed layout requires a compatible layout or a new multiplexer.
Per-container sizes are checked, so offset changes cannot hide behind an unchanged total.
Exact duplicate container objects are rejected; callers must also keep wrapper views disjoint.

ItemAction also compensates its shop-stock step if player-inventory publication fails.
Stock transactions already publish a new detached list, so the exact previous list can
be retained until the player-side write succeeds, preserving its metadata. The same
protection applies during refunds. This avoids restoring only player inventory while
leaving the failed action's stock credit/debit behind.

## Evidence and limits

Seven focused tests cover setter failure after mutation, notification failure, persistent
restore refusal, silent clamping, layout changes, duplicate sources, failed charges/rewards
and failed refunds. The original multiplexer fails all five base tests; the original
ItemAction fails both transfer tests even with the fixed multiplexer. Native settlement
regression passes 31 checks on NeoForge 21.1.233 and 21.1.248. Local evidence is summarized
in `artifacts/inventory-publication-validation-summary.json`.

Private-stock regression passes 28 checks and command rollback passes 26. All 332 ordinary
tests and 100 Interactions tests pass without failures/errors/skips, followed by a successful
clean test/build. Release archives exclude fixtures. All 78 Interactions entry names/contents
remain unchanged, including its versioned manifest; ZIP container hashes differ. The original
Citizens save retains SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

This is local slot-value recovery, not a universal transaction protocol for arbitrary
provider effects. World changes, external balances, hidden aliasing behind distinct wrapper
objects and side effects emitted by custom container callbacks cannot be undone just by
restoring slots. Recovery failures remain visible and require reconciliation. Physical-client/
provider acceptance and full reload transactionality remain separate work.

Interactions remains an independent NeoForge JAR with dependency Interactions → Citizens.
