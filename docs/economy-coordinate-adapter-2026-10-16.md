# Coordinate shops from NPCs and dialogues

Economy 0.5.2 publishes `CoordinateShopApi` for exact stable-ID or dimension/block
lookup, read-only validation and opening its ordinary coordinate-shop menu.
Citizens and the separate Interactions mod use the optional capability through
the existing adapter:

```text
/citizens economyshop coordinate <configured-shop-id>
/npc command add -p citizens economyshop coordinate <configured-shop-id>
```

```yaml
actions:
  - 'economy_shop: coordinate;<configured-shop-id>'
```

The source configuration/store determines the ID. Older/absent providers fail
without guessed commands, position inference or duplicate shop presentation.
Dialogue preflight checks the destination before payment, and execution validates
again. Ordinary/packet NPCs use a player source; the provider grants no remote or
operator authority.

Economy enforces the configured QuickShop interaction distance, the same dimension,
loaded container, resolvable trade item and current disabled-shop management
authority. The default remains six blocks for existing config files. Shop owners,
staff and operators are rechecked on every management click, along with current
price/quantity/owner fields. Old owner/staff menus cannot overwrite later changes.
Replaced/closed menus and different actors cannot execute the captured actions.

The actual-provider fixture covers immutable ID/position queries, read-only
preflight, exact menus, owner/staff revocation, latest-field preservation, stale
menus, disabled-shop owner reenabling, range/dimension/container/item failures,
thread/offline/provider retirement, ordinary/packet NPC clicks and dialogue payment
preservation. Physical-client acceptance and commerce/recovery/quest/craft gates
remain under the adopted backlog.
