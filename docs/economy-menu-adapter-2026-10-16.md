# Economy menus from NPCs and dialogues

Follow-up: [Economy 0.5.2 coordinate targets](economy-coordinate-adapter-2026-10-16.md)
add `coordinate;<shopId>` actions and `economyshop coordinate <shopId>` commands.
The system-menu contract below remains unchanged.

Citizens now opens Economy's existing menus with a player-source command:

```text
/citizens economyshop browser 0
/citizens economyshop shop "A shop"
/citizens economyshop page blocks "资源.items.special"
/citizens economyshop search "资源" 0
/citizens economyshop quantity blocks opaque-api-offer buy
```

For an NPC click, add the chosen command using `/npc command add -p`, for example:

```text
/npc command add -p citizens economyshop page blocks "资源.items.special"
```

The clicking player's UUID reaches Economy; there is no operator elevation or
console recipient inference. Existing command costs/cooldowns retain their normal
queue-admission policy. Opening the menu executes no purchase.

The separate Interactions mod adds `economy_shop` actions using its existing
semicolon field syntax:

```yaml
actions:
  - 'economy_shop: browser;0'
  - 'economy_shop: shop;A shop'
  - 'economy_shop: page;blocks;资源.items.special'
  - 'economy_shop: search;资源;0'
  - 'economy_shop: quantity;blocks;opaque-api-offer;buy'
```

Configure one intended target per action. Browser/search indexes are zero-based.
IDs and page/offer keys remain exact; `buy` and `sell` select the provider's trade
direction. Labels and item/currency details come from Economy. These example IDs
belong to the isolated fixture and must be replaced with configured catalog IDs.

Dialogue preflight calls Economy validation before any payment action runs, and
execution validates again. Invalid IDs/fields, missing capability, provider
retirement and offline actors fail without preflight menu/account/money changes.
Preflight reserves nothing; changes after payment can still reject execution.
Use the dedicated action when preflight of the destination is required. Arbitrary
`player_command` actions retain the native dispatcher validation policy.

The optional adapter acquires `YuuniverseEconomyApi.current()` for every request,
retains no facade and loads provider types only after capability discovery. The
five-target interface requires Economy 0.5.1-SNAPSHOT. A provider with only the
original two-argument API can handle the exact `shop` target. Older/absent providers
reject unsupported targets. Legacy `player_command_as_op: shop <id>` dialogue
routing now uses the same provider acquisition path.

This completes the system-menu consumer slice of ADAPT-02. Coordinate shops,
commerce receipts, quest/crafting/item adapters and physical-client acceptance
remain required by the adopted backlog.
