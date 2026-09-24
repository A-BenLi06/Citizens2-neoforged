# Native menu shift transfers

Validated: 2026-09-24 20:26:52 +08:00 (UTC+08:00), Minecraft 1.21.1 / NeoForge 21.1.248.

Shift-clicks now merge across available slots, skip denied destinations and use the actual menu-side pickup/placement callbacks. Previously the port stopped after its first candidate, used `PLACE_ALL` for partial insertion, used `MOVE_TO_OTHER_INVENTORY` for extraction and omitted the clicker/container context. A handler closing the menu or replacing either stack could also be followed by a stale transfer.

## Transfer and callback contract

The native container owns the transfer. Existing compatible stacks are filled before empty slots, following Minecraft's chest transfer order: forward into the menu, reverse through the player's hotbar and inventory when extracting. Transfers obey item component identity, native stack limits, destination slot capacity and `mayPlace`, and source `mayPickup`. Repeated empty destinations can receive the remainder when custom slots have lower limits.

The original Citizens API's callback vocabulary is preserved per actual transfer:

| Menu side | Entire remaining source fits | Only part fits | Callback current item | Callback cursor snapshot |
| --- | --- | --- | --- | --- |
| Destination | `PLACE_ALL` | `PLACE_SOME` | Destination stack | Exact incoming portion |
| Source | `PICKUP_ALL` | `PICKUP_SOME` | Remaining source stack | Player's real cursor |

Callbacks retain `SHIFT_LEFT` or `SHIFT_RIGHT`, the actual player/viewers and access to the active container through `setCursor`. Their predicted result reflects the exact proposed change. The incoming portion is only an event snapshot; the player's real cursor is never temporarily replaced. A handler explicitly changing the real cursor stops the transfer and keeps its requested value.

A denied destination is skipped so later allowed slots can receive the source. Denial of a menu source stops further extraction. Completed earlier transfers remain completed, with their source remainder updated before subsequent callbacks. This permits a close callback to persist the actual remaining stock.

After every callback, the code validates the active menu/session, source/destination slot objects, stack identity/content and actual cursor identity/content. Changed slot permissions/capacity are rechecked before writing. Changed screens or inventories stop remaining transfers. Shift and collection share a recursion guard covering the entire bulk action. Native unfinished drag state consumes the next Shift click without moving items. Only the active container resends authoritative full state to correct client prediction.

The obsolete shift implementation in `InventoryMenu` was removed. The public direct `moveIntoPlayerInventory` helper remains available for callers that authorize their own programmatic moves; player input uses the per-slot event path.

## Reproduction and evidence

Prepare `tools/prepare-menu-shift-audit.ps1` from the repository root. It creates an isolated loopback server on `127.0.0.1:25604` with world `menu-shift-world`. The collection and general regression commands below require their separately prepared fixtures. From `neoforge`, run sequentially:

```powershell
$env:JAVA_HOME = 'C:/Users/ben_l/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/menu-shift-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/menu-collection-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The dedicated fixture admits a synthetic player through `PlayerList`, uses native protocol encoding and the actual `ServerboundContainerClickPacket` handler, and decodes full-state packets into detached snapshots. Independent authored counts and callback traces cover both transfer directions, allowed/denied partial and empty destinations, both Shift buttons, item/slot limits, component preservation, actual cursor preservation, source/destination/cursor mutations and replacement, screen lifecycle after an earlier completed transfer, recursion, drag interruption and real `InventoryViewer` stock editing in both directions.

The negative control in `artifacts/repair-menu-shift-initial248.log` fails `shift_merges_all_partial_stacks_before_empty_slot` against commit `cf0033d`. The fixed dedicated run passes **240 checks**. Native `AbstractContainerMenu`, `ChestMenu`, `Slot` and server click-handler sources are recorded with their source archive hash in `artifacts/menu-shift-native-source.txt`; the checked reference API is `reference/CitizensAPI/src/main/java/net/citizensnpcs/api/gui/InventoryMenu.java`.

## Validation

Sequential validation passes **240 Shift checks**, **136 collection regression checks**, **107 NPC assertions and 223 general runtime probes**, and **217 ordinary tests** with zero failures/errors/skips. Every Gradle process was terminal before the next began; final batch 86757 exited 0 and no audit task remains. Only a documentation line wrap changed after the dedicated Shift run; collection/general regression and normal build used the final files.

Release/source archives contain the menu implementation and exclude audit/provider fixture classes. Citizens SHA-256: `4988be39313a3d88614e4850a3a91f3fb1ea65b820ae1ba73c380be0565d76ca`; sources: `87ca5b91b8ee95341cc9a1ca592e36c2fcb346a6cd413814b409f3878d5af538`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Original Citizens saves.yml remains `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Evidence: `artifacts/menu-shift-validation-summary.json`, generated by `artifacts/summarize-menu-shift.ps1`, records source/reference/fixture/log/report/archive hashes, counts and the negative control. Unrelated dedicated suites retain their earlier evidence.

## Scope

Subsequent [native drag placement](menu-drag-placement-2026-09-24.md) adds final allocation callbacks and extends the shared gesture guards; its regression reruns the Shift fixture.

This change implements Citizens-owned menu behavior under the [ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md). It retains existing providers and does not deploy to production or change the default NeoForge version. Physical-client input/rendering, combined modpack/proxy acceptance, full drag behavior and native anvil input remain separate work. The broader goal remains active.
