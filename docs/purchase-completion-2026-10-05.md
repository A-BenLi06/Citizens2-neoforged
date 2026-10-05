# Purchase completion before notification

Shop purchases now record their per-player/global limits and release transaction
ownership before sending the successful receipt or posting NPCShopPurchaseEvent. These
notifications are attempted independently and failures are logged without undoing an
already settled purchase. A receipt formatter can no longer lose the purchase count,
and an event failure cannot make a limited item purchasable again. Post-commit listeners
can legitimately remove the owning NPC; synchronous destructive action callbacks before
commit remain subject to the existing admission guard.

Merchant transactions now use a bounded view of the actual native payment container.
Only the two payment slots are included, never the preview result. Costs therefore
update native payments before actions or listeners can close the screen. There is no
late copy-back that could overwrite callback changes or restore inputs to a closed menu.
The old menu is not broadcast after a callback replaced or closed it.
If an action closes the merchant while further actions/refunds remain, newly produced
items use a transaction-local escrow instead of the closed native slots. Later rollback
can still reclaim staged rewards. After the action pipeline ends, remaining escrow items
use vanilla inventory return/drop behavior, including when the player's inventory is full.
Successful settlement finishes before purchase notifications; failed purchases settle
remaining refunds when the merchant operation exits.

OpenShopAction refreshes the inventory multiplexer after closing a merchant, so later
actions and refunds see the payments vanilla returned to the player's inventory. Its
queued openings are owned by the transaction and canceled on rollback. The target must
be active, and dispatch checks the original player session is still connected. Existing
origin-screen closure remains an immediate side effect: rollback cancels the destination
opening but does not recreate that origin screen.

## Evidence

The [native fixture](../tools/purchase-notify-runtime-audit/README.md) passes 31 checks on
NeoForge 21.1.233 and 21.1.248. It verifies receipt/event failure isolation, limits,
payment visibility inside the event, single refunds, stale clicks, NPC removal after
commit, navigation success/rollback and full-inventory refunds/rewards. Negative controls
reproduce the old missing purchase record and late-payment-update failures, plus hidden
refunds without escrow. An initial implementation could not
access protected merchant slot constants from the view class; the native subclass now
passes its declared payment range into that view.

Regression passes 55 removal-preflight checks, 26 command-rollback checks and 33 shop-session
checks. All 320 ordinary tests and 100 Interactions tests pass without failures/errors/skips,
followed by a successful clean test/build. Release archives exclude fixtures. All 78
Interactions entry names/contents match the preceding release, including its versioned
manifest; ZIP container bytes differ. The original Citizens save retains SHA-256
`cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

This establishes native server state with mock connections, not physical-client
rendering or guaranteed delivery to every listener after another listener throws.
Arbitrary custom action side effects, durable settlement/dispatch and full reload
transactionality remain separate concerns. Evidence is summarized in
`artifacts/purchase-notify-validation-summary.json`.

Interactions remains an independent NeoForge JAR with dependency Interactions → Citizens.
