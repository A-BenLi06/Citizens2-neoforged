# Public server scripts and FTB XMod callbacks

`net.citizensnpcs.api.integration.CitizensScripts` is a versioned public Java
facade, available from KubeJS using `Java.loadClass`. API version 1 exposes
current recipe snapshots, visibility-filtered FTB task discovery/state, explicit
native consuming delivery and Economy-owned menu validation/opening. Calls need
an active ServerPlayer on the server thread. An unregistered or disconnected
object with the same UUID cannot use a retired session. No KubeJS class enters
the production classpath, so Citizens loads independently of that provider.

```javascript
const Citizens = Java.loadClass('net.citizensnpcs.api.integration.CitizensScripts')
const Arrays = Java.loadClass('java.util.Arrays')
// Use the actor supplied by the owning event, and configured exact content IDs.
const state = Citizens.task(player, configuredTaskId)
const recipe = Citizens.recipe(player, configuredRecipeId)
Citizens.validateEconomyMenu(player, Arrays.asList('shop', configuredShopId))
Citizens.openEconomyMenu(player, Arrays.asList('shop', configuredShopId))
```

Queries never create craft evidence or grant quest progress. `deliver` consumes
only an available, visible native consuming ItemTask's required items; CustomTasks
and possession/crafting-only tasks are rejected. Recipe snapshots retain the
native revision and defensive item/component copies. A later request reacquires
current provider state. Economy menu opening does not trade, credit or grant
quest rewards. For trade and monetary operations, use Economy's own public API
and its accepted receipt/idempotency contracts.

The selected FTB XMod Compat 21.1.12, KubeJS 2101.7.2-build.377 and Rhino
2101.2.8-build.91 expose `FTBQuestsEvents.customTask`, `customReward`, `started`
and `completed` as targeted server events. The target for an exact object is
FTB's full hexadecimal `getCodeString()`; no imported Bukkit ID is inferred.
`customTask` configures the native CustomTask checker, maximum and timer.
`customReward` receives the actual claim actor. KubeJS player UUID access uses
its `uuid` property in this provider version. The native `started`/`completed`
events carry team state; their optional `player` comes from FTB's current player
context. Journal projection/reconciliation can run outside that context and
does not invent an actor for those events. Use retained craft evidence for
attribution, and the actual claim event for reward beneficiaries.

The isolated `tools/xmod-runtime-audit` script registers all four real Rhino
callbacks. Native result-slot consumption projects a recipe-bound CustomTask,
triggers start/completion, and retains evidence through reopen. A decoded native
claim message invokes the actual reward script, gives its test item and opens
Economy's browser without changing balance. Duplicate native claims and the
next repeat cycle are exercised. The fixture records callbacks from scripts;
it does not directly post replacement XMod events.

FTB's repeatable claim resets task progress before calling `customReward`; the
callback observes progress zero. A simple script credit cannot supply durable
monetary claim generations across this boundary. No production monetary callback
or general progress setter is supplied here. The fixture's item reward is only
runtime callback evidence, with no crash/restart guarantee. Full script/quest
reload, party/reconnect/restart, process-kill recovery and physical-client
acceptance remain required by ADAPT-04/05/11. Exact provider/release/log hashes
are retained in `artifacts/xmod-script-validation-2026-10-18.json`.

The native suite passes 25 checks on the actual deployment target, NeoForge
21.1.248. The complete selected fixture cannot start on 21.1.233: JEI's minimum
is 21.1.238 and Economy 0.5.5 requires 21.1.247. The rejected startup is retained
as evidence; no dependency descriptor is rewritten. Citizens' ordinary build
continues to target 21.1.233.

Monetary follow-up 2026-10-18, UTC+08:00: the [native claim bridge](ftb-monetary-rewards-2026-10-18.md)
now records exact beneficiary/generation and provider receipts before CustomReward
callbacks. The extended native fixture passes 44 checks and the actual killed-JVM
recovery passes 5 restart checks. This does not make arbitrary script item effects
durable or complete all script/content reload and physical-client acceptance.
