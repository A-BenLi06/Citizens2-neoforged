# Yuuniverse Economy GUI: API requirements and ordered implementation tasks

Reward follow-up 2026-10-18, UTC+08:00: [native FTB monetary bindings](ftb-monetary-rewards-2026-10-18.md)
now use Economy's exact credit/idempotency contracts with durable pre-reset claim
identity and native acceptance. Actual ledger-commit/lost-ack process kill recovery
and older FTB cycle fencing pass. This does not complete commerce task application,
all crash/backup domains or the full ECO-09/CRAFT-05/ADAPT-11 acceptance matrix.

Craft task follow-up 2026-10-18, UTC+08:00: [explicit FTB craft bindings](ftb-craft-evidence-projection-2026-10-18.md)
provide production recipe-revision bindings, durable team/cycle generations,
authenticated evidence review and absolute native CustomTask projection. Monetary
reward operations, commerce bindings, machine adapters and process-kill/rollback
acceptance remain required under CRAFT-04/05 and ECO-09.

Decision recorded: 2026-10-12, UTC+08:00.
Confirmed as the selected porting target: 2026-10-13, UTC+08:00. See the [adopted integration backlog and installation record](ecosystem-adaptation-goals-2026-10-13.md).
Update: v0.5.0 has now passed its 79-test build, the combined-stack startup and 36 actual-provider service checks; its upgrade is [prepared for the next planned restart](economy-v050-preparation-2026-10-13.json). The APIs proposed below remain development requirements, not newly implemented signatures.
Target: Minecraft 1.21.1 / NeoForge; consumer projects are Citizens, the separate Yuuniverse Interactions mod, FTB Quests and KubeJS/FTB XMod Compat.

Task progress 2026-10-18, UTC+08:00: [native task discovery](ftb-task-discovery-2026-10-18.md)
adds current-team visibility, prerequisites, native counts, task kinds and reward
ownership/status to NPC/dialogue listing and details. Repeatable cycle observations
are not durable monetary claim generations; that CRAFT-01 requirement remains open.

Craft evidence progress 2026-10-18, UTC+08:00: [native result observations](crafting-evidence-2026-10-18.md)
attribute actual table/player-grid takes to recipe ID/revision, actor, components and
complete batch quantity, including shift crafting and partial-inventory drops. The
hook precedes input consumption. Durable task-generation admission, replay/recovery
and recipe-specific advancement remain open under CRAFT-04/05.

Recipe progress 2026-10-18, UTC+08:00: [native discovery and guidance](crafting-recipe-discovery-2026-10-18.md)
now queries the active recipe manager, snapshots components/tags, checks revisions
and follows an actual KubeJS script/resource reload. CRAFT-02 lookup is implemented
for the documented capabilities. Machine adapters, craft evidence, full task
discovery/advancement and durable currency rewards remain required.

Coordinate progress 2026-10-16, UTC+08:00: the [0.5.2 follow-up](economy-coordinate-adapter-2026-10-16.md)
adds local exact lookup/opening and current management authority to NPC/dialogue
consumers. Commerce/quest, remote administration and full release acceptance
remain provider work. Historical pending-coordinate notes below describe 0.5.1.

Consumer progress 2026-10-16, UTC+08:00: [NPC commands and dedicated dialogue
actions](economy-menu-adapter-2026-10-16.md) now open all five 0.5.1 system-menu
targets, with provider reacquisition, read-only dialogue preflight and no direct
ledger/menu implementation access. Coordinate and commerce/quest contracts remain
the next provider work.

Progress 2026-10-15, UTC+08:00: Economy **0.5.1-SNAPSHOT** now implements the
system-shop portion of ECO-01–03: optional `SystemShopApi`, version discovery,
typed rejection, immutable queries and browser/exact-page/search/quantity menu
requests. Its authoritative contract is the neighboring Economy project's
`docs/system-shop-api.md`. The 81-test Economy build and 68-check actual-provider
fixture pass. Coordinate access/management, additional permission and mod-item
validation, quote/execution/recovery and quest/crafting integration remain open.
This source build has not replaced the separately prepared v0.5.0 upgrade or the
live 0.4.1 provider. The proposed contracts below remain backlog except for that
explicitly documented subset.

**Yuuniverse Economy owns the replacement for both EconomyShopGUI Premium and QuickShop:** shop browsing, quantity selection, buying/selling, coordinate shops, administration, stock, limits, tax, money and delivery. Consumers open Economy's menus or request Economy-owned transactions. They must not construct another shop GUI, keep parallel balances/stock, or manipulate the database directly.

This is a task specification, not a declaration that the proposed APIs already exist. It is based on the clean Economy source at commit `072703c` (`0.5.0-SNAPSHOT`) and read-only inspection of the installed `0.4.1-SNAPSHOT` JAR. No Economy code, database or deployment is changed by this document.

## What already exists

| Layer | Verified current implementation | Remaining integration need |
|---|---|---|
| System-shop GUI | Internal `EconomyMenus.openShopBrowser`, `openShop`, `openShopSearch`, quantity menus and sell GUI; imported pages, hidden sections, dynamic prices/stock and limits. | Publish supported browser/page/search entry points and immutable queries. Reuse the menus. |
| Coordinate-shop GUI | Internal `EconomyMenus.openCoordinateShop`; `/qs` opens the targeted shop, owner/staff management and restocking already have internal paths. | Publish stable coordinate-shop lookup/opening and permission-checked management services. |
| Monetary API | Source `EconomyApi` exposes currencies, exact conversion, account resolution, balances, credit/debit/transfer/set/adjust, durable operation keys, Vault/Gems facades and availability. | Reuse these contracts; add capability/version discovery and the commerce-specific facade. |
| System-shop API | Source `validateSystemShop(UUID, String)` and `openSystemShop(UUID, String)` exist. | They are absent from the installed 0.4.1 interface. Select and validate a newer provider before deploying consumers that require them. |
| Commerce | `CommerceService.execute(ServerPlayer, CommerceRequest)` coordinates inventory and ledger. `SqliteLedger` implements quote, execution, replay, stock/limit updates and delivery claims. | Expose a supported facade through the provider API. The public concrete `LedgerEconomyApi.ledger()` accessor is not a safe substitute: calling SQL-layer commerce bypasses Minecraft inventory coordination. |
| Existing data types | `CommerceRequest`, `CommerceQuote`, `CommerceReceipt`, `CoordinateShop`, `ShopDefinition`, `ShopPage`, `ShopOffer`, `DeliveryClaim`. | Reuse where their semantics fit; distinguish stable public views from mutable/internal implementation details. |
| Existing GUI protocol | Vanilla container menus, translated components and server-side click handling. | New entry points should preserve that protocol. FTB Quests/Guard Villagers have separate client requirements. |

The recorded source migration includes 40 system-shop sections, 88 pages, 1,545 offers and 100 coordinate shops. These historical source counts define useful acceptance fixtures, not a new production-data census. One finite coordinate shop still requires physical stock reconciliation; an orphan dynamic-stock row must remain evidence rather than become a fabricated offer.

## Ordered tasks for the Economy project

Implement these as separate coherent commits, each with its own timestamped walkthrough entry and meaningful validation. Proposed names below describe the intended contracts and may be refined to fit the existing API.

| Order / ID | Priority | Deliverable | Existing basis | Acceptance |
|---|---|---|---|---|
| 1 / ECO-01 | P0 | Version/capability discovery and provider lifecycle. Add explicit supported commerce capabilities and typed failure results; preserve `current()`/`isAvailable()`. | Source already publishes/clears the active service; installed 0.4.1 has an older method set. | Missing/old/stopped provider fails clearly without charging, opening stale menus or retaining retired service references. Document the minimum tested artifact. |
| 2 / ECO-02 | P0 | Immutable catalog and shop queries: list/search system shops, pages/offers; resolve a coordinate shop by stable ID or dimension/block position; permission-filter results. | Internal ledger queries and complete menu/catalog implementation. | Hidden shops are directly addressable only when allowed; labels/Unicode do not change identity. Missing dimensions/items/shops are explicit outcomes. Reads create no accounts and mutate no stock/limits. |
| 3 / ECO-03 | P0 | Supported GUI opening: browser, exact system shop/page, search, coordinate shop, and offer quantity menu. Preserve existing two-argument system-shop API. | Internal `EconomyMenus`; source public system-shop validation/opening. | NPC/dialogue opens the same Economy GUI as `/shop` or `/qs`; invalid targets leave the current menu unchanged. Actor, online session, distance/remote-access policy and permission checks are identical to ordinary GUI use. |
| 4 / ECO-04 | P0 | Commerce preflight/quote contract: exact currency and totals, trade count versus physical item count, fees/tax, available stock, remaining limits, eligibility, offer revision and validity policy. | Existing `CommerceQuote` has currency, total, quantity and player-limit fields. | Quote has no payment/inventory effects. Confirming after a relevant price/stock/permission change rejects or requests reconfirmation; it cannot silently charge a different accepted total. Numeric overflow and invalid quantities reject before effects. |
| 5 / ECO-05 | P0 | Public commerce execution facade routed through `CommerceService`, returning durable receipts and explicit committed/replayed/rejected/delivery-pending state. | Inventory-aware service, SQL transactions, `CommerceReceipt` and replay records. | Same request/key replays the same result; a different payload reusing a key fails. Money, tax, virtual stock, limits and claims remain one ledger transaction. Mod items/physical inventories use the existing service, never a direct SQL-only call. |
| 6 / ECO-06 | P0 | Delivery and recovery API: query/claim pending delivery, query an operation outcome and expose recovery-required cases. Audit crash boundaries around physical container/player inventory changes. | Existing delivery claims, `deliverPending`, inventory restoration and reconciliation. | Full inventory, disconnect, retry/restart and crashes at each inventory/SQL boundary do not duplicate goods or lose paid entitlement. World inventory and SQLite are separate persistence domains; add durable recovery records where current handling cannot prove recovery. |
| 7 / ECO-07 | P1 | Permission-checked management facade and Economy-owned admin menus: offer enable/prices/stock; coordinate create/remove/owner/staff/currency/item/mode; fee and tax policy. | Existing `/shopadmin`, `/editshop`, `/qs` and corresponding ledger mutation methods. | Explicit acting UUID and authoritative checks on every operation; an NPC/script is not implicit operator authority. Fees and the associated mutation commit together. Registered containers remain protected during cancellation/reload. |
| 8 / ECO-08 | P1 | Post-commit commerce events and durable subscription/replay support sufficient for quest consumers. Include operation ID, actor, shop/offer, action, currency, quantities and origin. | Existing operation/audit receipts; no supported general commerce event facade identified. | Consumers observe committed outcomes, deduplicate by operation ID, and reconcile missed delivery after restart. A callback error cannot roll back an already committed purchase or emit another payment. Logs expose no private account data by default. |
| 9 / ECO-09 | P1 | FTB Quests/KubeJS adapters: exact monetary requirement/reward and shop/delivery task actions using the public facade. | Current monetary API; FTB XMod Compat customTask/customReward/started/completed integration. | Define player versus team ownership. A reward key includes stable quest/reward/claim generation and beneficiary identity; party changes/repeatable quests do not duplicate or incorrectly suppress rewards. Quest completion follows accepted receipt/state, not a successful command dispatch. |
| 10 / ECO-10 | P1 | Optional Paradigm placeholder registration for selected configured balances/shop values; immutable snapshots for non-server-thread formatting. | Paradigm's installed public registration API; existing Citizens adapter demonstrates lifecycle ownership. | Read-only, permission-aware formatting using configured currency precision/labels; unregister on shutdown/reload. Inbound placeholder evaluation is a separate capability. Never add a placeholder-driven alternate price or ledger authority. |
| 11 / ECO-11 | P1 | RSItemEditor library bridge for explicitly referenced saved items, using complete native component snapshots. | Native item component support, existing legacy item conversion; RSItemEditor NBT-backed library. | Stable namespaced item references resolve to defensive copies. Changing/deleting a library item follows an explicit snapshot/reference policy. Missing content rejects the offer while preserving its original definition. No direct edits to another mod's NBT while active. |
| 12 / ECO-12 | P1 | Migration report and integrated acceptance against the selected release/client/provider set. | Existing importers/reconciliation reports and shop compatibility fixtures. | Resolve real source IDs/accounts, reconcile balances/stock, prove one system-shop and one finite coordinate-shop workflow, NPC/dialogue opening, quest receipt consumption and restart recovery. Unsupported data is reported with provenance. |

Dependency order: ECO-01 → ECO-02/ECO-03 → ECO-04/ECO-05/ECO-06 → ECO-08/ECO-09. Management and text/item adapters can follow the stable facade. Do not delay safe GUI opening until every optional adapter is implemented.

## Proposed public contract shape

These are design names, **not currently callable signatures**:

```java
interface CommerceApi {
    ShopLookupResult lookupShop(ShopQuery query, ActorContext actor);
    CatalogPage queryCatalog(CatalogQuery query, ActorContext actor);
    MenuOpenResult openMenu(UUID player, MenuRequest request);
    QuoteResult quote(UUID player, PurchaseIntent intent);
    CommerceResult execute(UUID player, AcceptedQuote quote, OperationContext operation);
    OperationResult findOperation(UUID player, String operationKey);
    DeliveryResult claimDeliveries(UUID player, DeliveryRequest request);
    Registration subscribe(CommerceListener listener);
}
```

Expose the service through the existing `YuuniverseEconomyApi.current()` entry point, with optional capabilities or an additive accessor. Preserve existing callers and document the binary/source compatibility policy. Consumers receive public views/results, not `SqliteLedger`, database paths, `EconomyRuntime`, mutable menu internals or live shared `ItemStack` instances.

Required data semantics:

- **Identity:** UUID actors; stable system/coordinate shop references; namespaced dimensions and item IDs; legacy IDs remain opaque strings. Display labels never become account/shop identities.
- **Money:** currency ID and `long` minor units or exact `BigDecimal` conversion. Scale/symbol/tax/fees come from provider configuration. Display rounding must not change payable amounts.
- **Quantity:** separately name trade count, items per trade and physical quantity. Validate multiplication and bounds before inventory/ledger changes.
- **Authorization:** actual acting player and permission context. Origin metadata such as NPC ID or quest ID is audit information, not authority. Foreign task data cannot grant operator privileges.
- **Operation identity:** caller-stable idempotency key plus origin reference and request fingerprint; server issues/verifies quote identity. Do not mint a new random operation key on each retry of the same economic intent.
- **Results:** machine-readable error code plus translatable message key/arguments; receipt includes committed/replayed status, operation/transaction/claim IDs and actual paid quantities. Opening a GUI is not a completed purchase.
- **Threads/lifecycle:** all world/player/menu/inventory access stays on the server thread. Database work may be delegated only with a defined completion boundary; old sessions/provider instances cannot publish stale results.
- **Events:** post-commit notifications carry stable operation identity and support reconciliation. Do not promise exactly-once external callbacks; use at-least-once delivery plus consumer deduplication when reliable delivery is needed.

## Consumer task list

| Consumer | Adapter task | Completion example |
|---|---|---|
| Citizens | Use Economy monetary/commerce contracts for NPC entry/costs; preserve ordinary NPC-owned inventory shops where they are a distinct Citizens feature. | Clicking a configured economic-shop NPC opens the corresponding Economy menu without a second balance, stock store or copied UI. |
| Yuuniverse Interactions | Add typed system/coordinate-shop targets and receipt-aware economic actions in the separate dialogue JAR. | A dialogue opens a shop by stable ID; missing shop/account/currency has a defined failure branch and no payment. |
| FTB Quests + FTB XMod Compat | Native/custom task and reward bridge to Economy; define team/individual semantics. | One paid purchase or item delivery advances one intended task and survives reconnect/restart without duplicate reward. |
| KubeJS | A small binding around the same public service, with structured errors and no direct database/reflection access to internals. | Scripted reward uses a stable operation key and observes the same receipt on replay. |
| Paradigm | Registration and lifecycle for approved text values; permission-provider alignment for shop/admin actions. | Revoking permission invalidates subsequent confirmation, and placeholder reads never create financial state. |
| RSItemEditor | Resolve explicitly selected saved items into immutable item snapshots through a documented adapter. | An item with custom components is displayed and delivered identically; an unknown item remains unavailable. |
| NeoProtect + GriefLogger | Define attribution for ordinary block/container events and financial audit correlation. | Rollback of world blocks does not automatically reverse or duplicate commerce ledger entries. Any compensating financial action is a separate audited Economy operation. |

## Concrete first Economy task

**Publish supported shop lookup and menu entry points (ECO-01 through ECO-03), reusing existing EconomyMenus.**

Implement capability/version discovery, permission-aware system/coordinate shop lookup, and browser/system-page/coordinate menu opening. Preserve `openSystemShop(UUID, String)`. Use stable IDs and configured content. Add focused tests for unknown shops, offline players, permissions, Unicode IDs, page validation, provider shutdown and unchanged menus on rejection. Then validate one native NPC/dialogue opening path with the selected provider JAR. Record remaining quote/payment/recovery work explicitly; this first task does not implement the whole backlog.

## API demands for listing mod crafting tasks

The ECO tasks above are the ordered **mod-development backlog**. If a quest also asks a player to craft a modded item, its recipe, progress and reward require the following additional contracts. These are proposed adapter requirements; Economy currently has no verified public crafting-task API.

FTB Quests owns task definitions and player/team progress. Minecraft's recipe manager and each machine mod own recipes and craft execution. Economy owns purchase/sale settlement and financial rewards. The Economy GUI may link a task to an item offer, required materials or a reward; it must obtain progress from FTB and recipe facts from their providers.

| Order / ID | Required contract | Consumer and acceptance condition |
|---|---|---|
| 1 / CRAFT-01 | Task discovery: stable quest/task ID, task kind, player/team owner, prerequisites, visibility, repeat/claim generation, localized title and progress snapshot. | NPC/dialogue/task listing shows only tasks visible to the actor, uses FTB's authoritative counts, and survives quest reload/ID migration. Separate craft, possession, delivery, purchase and sale tasks. |
| 2 / CRAFT-02 | Registry-aware recipe lookup by recipe ID/type and output, after datapack/KubeJS recipe loading. Return ingredient alternatives/tags, quantities, output component snapshots, recipe revision and supported workstation. | Vanilla shaped/shapeless recipes and registered mod recipes are discovered from the active server. Datapack reload invalidates stale results; missing recipes/components stay unavailable instead of becoming vanilla substitutes. A recipe viewer is not authoritative server data. |
| 3 / CRAFT-03 | Optional recipe-type adapters for machine processes: item/fluid inputs, catalysts, remainders, duration/energy and probabilistic outputs where the provider exposes them. | Capability discovery marks unsupported recipe types explicitly. Do not claim that a crafting-table event covers Create processing, assembly or another mod's machines. Keep provider-specific fields in typed extensions, not hardcoded global item/mod lists. |
| 4 / CRAFT-04 | Authoritative craft/delivery evidence: actor or attributable machine owner, recipe ID/revision, actual output/count, stable observation ID and task-generation reference. | Count completed output once, including shift crafting and multi-output recipes. Buying, picking up or moving an identical item does not satisfy a craft-only task. Unattributable automation follows an explicit pack rule. Use a durable reconciliation strategy for provider events that cannot be replayed; document any crash window. |
| 5 / CRAFT-05 | Validated task advancement and reward claim through FTB/native or XMod custom task/reward hooks. Specify consuming delivery separately from non-consuming crafting progress. | Party membership, repeatable quests, restart, reconnect and duplicate events preserve intended progress. Item consumption cannot be repeated by a retry. A reward's Economy operation key includes beneficiary and claim generation; money is not issued merely because a task appears complete in the GUI. |
| 6 / CRAFT-06 | Economy linkage: material/offer lookup, exact quote, Economy-owned menu opening, operation lookup and reward receipts (ECO-02–ECO-06, ECO-08–ECO-09). | A task's “buy materials” route opens the existing Economy GUI. Buying ingredients does not auto-complete crafting; opening a menu creates neither a purchase nor a quest reward. Permission/price/stock changes receive the same revalidation as ordinary trades. |

First vertical acceptance: configure a real recipe from the target modpack by its registry ID; list its task for an eligible player/team; open an Economy material offer from the NPC/dialogue; craft the configured output; advance exactly the matching FTB task; claim a currency reward; replay/reconnect/restart without duplicate progress or payment. Repeat with a component-sensitive item and one explicitly supported machine recipe. This is an adaptation acceptance goal, not a claim that installation implements it.

Do not expose the proposed Java types as fake callable KubeJS examples. Pin the installed FTB/XMod artifacts, inspect their public bindings, and publish executable examples only when the adapter exists. All user-facing labels use provider components/translations; IDs, currency scales and task definitions come from configuration/registries.

## Inspected source locations

In the neighboring `yuuniverse-economy` repository: `src/main/java/net/yuuniverse/economy/api/{EconomyApi,YuuniverseEconomyApi,LedgerEconomyApi}.java`, `EconomyMenus.java`, `EconomyRuntime.java`, `CommerceService.java`, `QuickShopCommands.java`, and `core/{CommerceRequest,CommerceQuote,CommerceReceipt,SqliteLedger}.java`. The existing `docs/economyshopgui-compatibility.md`, `docs/quickshop-compatibility.md`, and `docs/legacy-economy-api-coverage.md` describe the original source scope.

Installed `EconomyApi` was independently inspected with JDK 21 `javap`: 0.4.1 exposes basic currency/account/monetary operations but lacks the source's shop-opening and newer facade methods. Upgrade/migration validation is an explicit prerequisite; this specification does not authorize assuming source features are deployed.

Progress 2026-10-18, UTC+08:00: [craft outcomes and journal](quest-evidence-journal-2026-10-18.md)
add return-scoped native evidence, durable deduplication/generation storage,
append-only transitions and explicit review of interrupted/missing outcomes.
Production binding/projection and authenticated review are now implemented by the
[explicit craft binding adapter](ftb-craft-evidence-projection-2026-10-18.md).
Monetary claims and process-crash/world-rollback acceptance remain required;
CRAFT-04/05 are incomplete.
