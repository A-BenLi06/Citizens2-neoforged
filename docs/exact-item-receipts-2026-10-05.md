# Exact item transfer receipts

ItemAction now separates admission matching from transfer/refund identity. A configured
filter can accept a broader set of items, but payments carry the actual consumed stacks
into limited stock. Limited-stock rewards likewise deliver the selected stock variants.
Unlimited rewards still generate the configured templates. Capacity planning uses the
actual component variants, which may need more slots than a normalized template.

Each successful execution records an exact receipt. Refunds use full component equality,
including damage, lore and repair cost; they cannot consume another item merely because
it matches the original loose filter. Receipts are independent of later action edits and
are consumed in reverse execution order. A failed refund retains its receipt for retry;
an already completed refund is not applied twice. Existing stock/player publication
compensation remains in use.

This deliberately preserves real finite-stock items rather than normalizing them into
the configured reward template. The shop's configured display icon is still an authoring
choice and is not dynamically rewritten for every stock variant. If an external action
changes or removes the recorded item before refund, the refund reports failure instead
of substituting a different variant.

## Evidence

Six new unit tests pass; original code fails all six. They cover actual cost metadata,
ignored repair cost, exact reward refund identity, finite-stock rewards, component-aware
capacity, configuration edits and repeated executions. The
[native fixture](../tools/item-receipt-runtime-audit/README.md) passes 11 checks on each
of NeoForge 21.1.233 and 21.1.248 with a mock-connected player using chest and merchant
menus. Local evidence is summarized in `artifacts/item-receipt-validation-summary.json`.

Quantity and settlement regressions pass 16 and 31 checks. All 349 ordinary tests and
100 Interactions tests pass without failures/errors/skips, followed by a successful clean
test/build. Release archives exclude fixtures. All 78 Interactions entry names/contents
remain unchanged, including its versioned manifest; ZIP container hashes differ. The original
Citizens save retains SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Arbitrary external item changes, physical-client/provider acceptance and full reload
transactionality remain separate work. Interactions remains an independent NeoForge JAR
with dependency Interactions → Citizens.
