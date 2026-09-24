# Native menu drag placement

Validated: 2026-09-24 20:42:59 +08:00 (UTC+08:00), Minecraft 1.21.1 / NeoForge 21.1.248.

Citizens menu drag placement now authorizes the actual final allocations. Previously candidate admission was routed as an `UNKNOWN` click, then Minecraft completed the gesture without consulting the menu again. A slot locked after admission still received items; editors consuming predicted results could also change equipment during admission, before any inventory move occurred.

The reference Citizens API cancels inventory dragging entirely. This port already exposes editable stock and equipment menus. Their native drag support therefore needs real final placement decisions, rather than an admission-only approximation or a blanket drag prohibition.

## Native state and callback contract

Minecraft owns gesture start, candidate admission, duplicate suppression, count eligibility and malformed phase resets. The container reads its native `quickcraftStatus`, `quickcraftType` and `quickcraftSlots` through protected access transformers. It copies the final candidates and resets the native gesture before invoking menu callbacks, preventing close/reentry from replaying that gesture.

Single-candidate gestures follow the native ordinary-click path, including left placement, right placement and the native creative single-slot no-op. Multi-candidate gestures retain the native selected set and share calculation: equal shares for left drag, one item per slot for right drag, and native maximum fills for an authorized creative drag. Stack components, item/slot limits, `mayPlace`, `canDragTo` and creative eligibility are checked at completion. Current cursor contents and compatible destination counts are read at completion, as in Minecraft.

Each menu destination that would receive items gets one callback with the actual player/viewers, input direction, incoming portion and exact predicted result. Left drag uses `PLACE_SOME` or `PLACE_ALL`, right drag uses `PLACE_ONE`, and creative fill uses `PLACE_ALL`. The event cursor is a detached incoming portion; `setCursor` still modifies the real cursor and aborts the remaining proposals. Player inventory destinations remain governed by native slot rules.

Cancellation skips that destination. Its unused share stays on the cursor; it is not redistributed among the other selected slots. Native candidate iteration is preserved rather than inventing a stable menu order. After callbacks, changed menu/session ownership, target identity/content, cursor identity/content, placement rules or capacity stop stale proposals. Accepted shares are deducted before the destination is written, so a subsequent close returns only the unplaced cursor. Earlier accepted placements remain committed.

Shift, collection and drag share a bulk-action recursion guard. Ordinary clicks also consume unfinished drag state before invoking menu callbacks, matching native click ordering. Closing the container clears its native gesture. Malformed outside candidate indices reset safely instead of indexing outside the slot list. Full-state correction is sent only when the gesture finishes or resets, not during ordinary candidate admission.

## Reproduction

Prepare `tools/prepare-menu-drag-audit.ps1` from the repository root. It creates an isolated server at `127.0.0.1:25605` with world `menu-drag-world`. Other regression commands require their separately prepared fixtures. From `neoforge`, run sequentially:

```powershell
$env:JAVA_HOME = 'C:/Users/ben_l/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/menu-drag-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/menu-shift-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/menu-collection-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The fixture uses real `PlayerList` admission, native protocol encoding and actual `ServerboundContainerClickPacket` handling for every phase. Expected counts and callback traces are authored independently; captured full-state packets are detached through the native stream codec. Cases cover locks changed between packets, deferred callbacks, left/right/creative distributions, single-slot behavior, native slot/component limits, duplicate and malformed phases, input interruption, callback mutations, lifecycle after an accepted share, recursion, and actual stock/equipment editors.

The preserved negative-control log `artifacts/repair-menu-drag-initial248.log` fails `drag_rechecks_menu_lock_at_completion` against `a0eee3f`. The first expanded run passes 148 checks; final coverage adds completion-only correction, stored-mode and final-count rules, creative permission changes inside a callback, and native container listeners closing during a write. The final dedicated fixture passes **158 checks**. Native source evidence and its archive hash are recorded in `artifacts/menu-drag-native-source.txt`.

## Validation

Final sequential validation passes **158 drag checks**, **240 Shift checks**, **136 collection checks**, **107 NPC assertions and 223 general runtime probes**, and **217 ordinary tests** with zero failures/errors/skips. Every Gradle process was terminal before the next began; final batch 23867 exited 0 and no audit task remains. No production behavior changed after final dedicated validation.

Release/source archives exclude audit/provider fixture classes and contain all three native drag-state access transformers. Citizens SHA-256: `eea6b5c4f0a122fa8f7259da6ad44861480ac1ca94695a00c923f5e7169a797b`; sources: `c096b353736fdcb057d17a6b69e35e0567b30eb8d92914b3387b18b7098bfadc`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Original Citizens saves.yml remains `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Evidence: `artifacts/menu-drag-validation-summary.json`, generated by `artifacts/summarize-menu-drag.ps1`, records source/reference/fixture/log/report/archive hashes, the original failure and first expanded result. Unrelated dedicated suites retain their earlier evidence.

## Scope

This is a Citizens-owned native menu contract under the [ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md). No retained provider or production deployment changes are included. The default NeoForge version is unchanged. Physical-client drag preview/input/rendering and combined modpack/proxy acceptance remain unverified; native anvil input and other consumed Citizens contracts remain open. The broader goal stays active.
