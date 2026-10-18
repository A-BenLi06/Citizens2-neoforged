# Configured Economy and FTB values through Paradigm

Citizens' existing Paradigm bridge now accepts configured exports from
`config/citizens/placeholder-exports.json`. The file is optional; missing files
produce no extra registrations and are never created by the consumer. Definitions
load when the bridge installs. After editing, restart normally or explicitly
uninstall/install the bridge through its supported lifecycle API. Unknown fields,
unknown kinds, duplicate keys and reserved NPC keys reject the configuration
without modifying its bytes or registering a partial set.

```json
{
  "schema": 1,
  "exports": [
    {"key": "wallet", "kind": "ECONOMY_BALANCE", "currencyId": "configured_currency"},
    {"key": "craft_progress", "kind": "FTB_TASK_PROGRESS", "taskId": "0000000000010403"},
    {"key": "craft_maximum", "kind": "FTB_TASK_MAXIMUM", "taskId": "0000000000010403"},
    {"key": "craft_complete", "kind": "FTB_TASK_COMPLETED", "taskId": "0000000000010403", "permission": "pack.quest.read"}
  ]
}
```

Replace example IDs with the server's actual currency/task IDs. The resulting
Paradigm keys are `{citizens_wallet}`, `{citizens_craft_progress}`,
`{citizens_craft_maximum}` and `{citizens_craft_complete}`. Keys accept lowercase
letters, numbers and underscores. The four built-in Citizens NPC keys are reserved.

Balance exports require an exact native currency ID and existing player account.
They reacquire the public Economy API per snapshot and render exact major units
using that currency's scale, without rounding or account creation. Task exports
read the actual viewing player's current FTB team through the public task adapter.
Hidden tasks have no exported value. The optional permission applies on each
server-thread capture and uses the current native authority, including Paradigm.

Missing providers, unknown currency/task IDs, denied access and offline players
produce no value, which Paradigm renders empty. Provider values never fall back to
console selection or another player's account. No purchase, reward or progress is
created by a query. Worker-thread formatters read immutable snapshots from the
last completed server tick and never call live provider/world APIs; updates and
permission revocations can therefore lag one tick for those callers.

Configured keys use the same owned registration handles as built-in NPC keys.
Shutdown and provider replacement release all owned handles; collisions roll back
the partial registration batch without overwriting another owner's resolver.
Runtime provider retirement clears/omits stale values on the next capture.
This adds outbound values, not inbound text evaluation: installed Paradigm 2.5.0
still exposes registration without a supported evaluate/format result API.

Validation covers strict configuration, exact Economy scale/missing currency,
actual FTB team/hidden-task reads, permission filters, real Paradigm registrations,
worker snapshots, offline/team isolation, collision cleanup, provider replacement
and provider absence. Exact counts/provider/release hashes are recorded in
`artifacts/provider-exports-validation-2026-10-18.json`. Physical clients, arbitrary
formatting surfaces and a complete mod reload remain separate acceptance.
