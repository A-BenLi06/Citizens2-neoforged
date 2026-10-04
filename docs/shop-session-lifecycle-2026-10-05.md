# Shop session ownership

Successful shop publication now retires each previous shop object and permanently ends
its owned inventory menus and merchant screens. Failed source reads/decoding leave
existing screens usable. Retired shops cannot open new screens or execute retained
purchase callbacks. Deletion checks exact object identity so a stale shop cannot delete
its replacement. Shutdown closes shop sessions before saving, allowing current editor
state to be included while preserving definitions for persistence.

Shop sessions use weak targets and detach on ordinary closure. Menus remain tracked
through page transitions, including native anvil and chat-input pages. Permanent menu
disposal cancels chat prompts and deferred parent returns, blocks reopening, clears the
page stack, and attempts every close callback even after a failure. Reentrant disposal
does not repeat callbacks. Chest container validity now follows exact menu ownership,
as the native anvil container already did.

Merchant menus bind the trading player, reject stale clicks and become invalid when
their shop retires. Closing them uses vanilla removal to return payment-slot items once.
Ordinary shop purchases hold a transaction scope; preparation/publication rejects
replacement while a current shop transaction is running. A command action cannot
replace the stock object halfway through that purchase. This is a replacement guard,
not a universal transaction across external commands, providers or asynchronous code.

If an old NPC cleanup fails after registry publication, shop publication still runs so
its old menus are not left active. Cleanup errors are aggregated and reported after
the successfully restored bindings remain published. Shop close callbacks cannot
recursively prepare/publish, and shutdown prevents new built-in shop creation/session
admission while still allowing final persistence.

Built-in shop actions are now bootstrapped by the shop action engine, including during
shop preparation. They no longer depend on constructing a ShopTrait first. The isolated
global-shop fixture exposed the old registration failure before this change.

## Evidence

The [native fixture](../tools/shop-session-runtime-audit/README.md) uses three admitted
players with embedded connections. Both NeoForge 21.1.233 and 21.1.248 pass 33 checks:
live chest/merchant trades, preserved screens on failure, reload rejection within a
trade, successful closure, exact payment return, stale callback rejection, chat and
queued parent-return cancellation, throwing callback cleanup, deletion and shutdown.
Two additional unit tests cover publication despite one session's close failure and
rejection of replacement during a transaction.

Regression passes 113 native input-menu checks, 48 strict shop-load checks and 59 shutdown
checks. All 318 ordinary tests and 100 Interactions tests pass without failures/errors/skips,
followed by a successful default-version clean test/build. Release archives exclude
fixtures. All 78 Interactions entry names/contents, including its versioned manifest,
match the preceding release; rebuilt ZIP container bytes differ. The original Citizens
save retains SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Initial fixture compilation needed an explicit InventoryMenu import and initialise
implementation. Runtime checks then exposed missing global action registration and
the old chest container's unconditional validity. These failures are retained separately
from successful validation logs. Final evidence is summarized in
`artifacts/shop-session-validation-summary.json`.

This validates native server state through mock connections, not physical client
rendering, network latency or external economy/provider behavior. Direct modification
of public shop maps by third-party code bypasses the built-in ownership methods. Full
configuration/NPC/provider/shop/template transactionality remains separate work.
Default-NPC-store-only replacement also needs dedicated handling for views bound to
the old trait's private NPCShopStorage when the shop definition itself is retained;
this fixture exercises ordinary shop reload, deletion and shutdown, not that path.

Interactions remains an independent NeoForge JAR with dependency Interactions → Citizens.
