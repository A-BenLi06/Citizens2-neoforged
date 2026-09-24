# Native menu bulk collection

Validated: 2026-09-24 20:15:42 +08:00 (UTC+08:00), Minecraft 1.21.1 / NeoForge 21.1.248.

Citizens menus now apply their slot permissions when a player double-clicks to collect matching items onto the cursor. Previously a double-click originating in the player's inventory bypassed menu handlers, allowing Minecraft's collection loop to take locked menu display items.

## Behavior and native contract

`CitizensMenuContainer` checks each eligible menu source using `DOUBLE_CLICK` and `COLLECT_TO_CURSOR`. Default locks, action filters and callback cancellation protect that source; allowed sources and the player's inventory remain collectable. A callback can explicitly override its slot's filter, as with ordinary menu clicks. A double-click originating on a locked menu slot still cancels the whole click.

Collection preserves Minecraft 1.21.1's direction, partial-stack-first and full-stack passes, component matching, native stack limits, pickup eligibility and `Slot.safeTake`. A canceled source is offered once per collection. The source event includes the actual clicker, viewers, detached source/cursor snapshots and the proposed source remainder. Origin events predict no direct source removal; the subsequent source events describe each transfer.

The menu reads Minecraft's own `quickcraftStatus` through a protected access transformer. An unfinished drag consumes the next double-click by resetting the native drag state, matching `AbstractContainerMenu.doClick`. The following double-click may collect normally.

After source callbacks, collection rechecks exact container/session ownership, cursor identity/content and source identity/content. Closing or replacing the menu, transitioning pages, or replacing/mutating either stack stops remaining transfers. Recursive collection from both origin and source callbacks is guarded and the guard is released in `finally`. Retired player sessions and retired containers cannot initiate transfers. Transfers already completed before a later callback remain completed.

The active menu sends full state after collection to correct the vanilla client's optimistic collection of locked controls. Retired menus are not resynchronized over a newly opened screen. Actual stock editing remains supported: `InventoryViewer` allows collection from stock slots and saves the remaining inventory on close.

## Reproduction

Run `tools/prepare-menu-collection-audit.ps1` from the repository root. Its isolated server binds to `127.0.0.1:25603` with world `menu-collection-world`. From `neoforge`, run sequentially with the supplied JDK:

```powershell
$env:JAVA_HOME = 'C:/Users/ben_l/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/menu-collection-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The general runtime command requires the repository's separately prepared `artifacts/audit-server` fixture. Ordinary builds exclude all opt-in audit code. The repository's default NeoForge version remains unchanged.

The dedicated fixture admits a synthetic player through the real `PlayerList`, configures native protocol encoding and sends `ServerboundContainerClickPacket` through the real server handler. Authored expected stack counts and callback traces check locks/filters/overrides, editable slots, forward/reverse order, both stack passes, capacity, component identity, custom stack limits, origin/cursor preconditions, native drag interruption, callback mutations, recursion, transitions/close/replacement, retired sessions and the actual stock editor. Captured full-state packets are detached with the native stream codec; an optimistic client prediction that empties the locked control is corrected to the authoritative contents.

The preserved negative-control log, `artifacts/repair-menu-collection-initial248.log`, reproduces `double_click_cannot_collect_locked_menu_item` against the preceding committed implementation. The first expanded corrected run passed 121 checks; stock editor and component-limit coverage raised this to 131. Final review moved the recursion guard around the entire click, including origin callbacks. The final dedicated run includes that boundary and passes **136 checks**. The fixture does not run a physical client or its renderer.

## Validation

Final sequential validation passes **136 dedicated checks**, **107 NPC assertions and 223 general runtime probes**, and **217 ordinary tests** with zero failures/errors/skips. Each Gradle process was terminal before the next began; final batch 46457 exited 0 and no audit task remains. The final source change after dedicated validation only clarified the container class documentation; general regression and normal build used the final files.

Release/source archives contain the modified menu classes and native drag-state access transformer, and exclude audit/provider fixture classes. Citizens SHA-256: `72d2caec82a515e7d64104ae2878d0398af7d7b437b05f91b5733eb74e9dc6fc`; sources: `2aacc8bd8f9f4d821548e8cbcca61c4129f8953c329ece21a2fddaceffb4cb49`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Original Citizens saves.yml remains `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Evidence: `artifacts/menu-collection-validation-summary.json`, generated by `artifacts/summarize-menu-collection.ps1`, records source/fixture/log/report/archive hashes and the preserved negative control. `artifacts/menu-collection-native-source.txt` contains the inspected native container, slot and server packet-handler sources with their archive hash. Intermediate successful runs are retained separately from final validation.

## Scope

Subsequent work restores [native shift transfers and their callback contract](menu-shift-transfer-2026-09-24.md), including multi-slot merging and lifecycle checks. Its regression reruns this collection fixture.

This change implements a Citizens-owned native inventory contract under the [ecosystem guidance](neoforge-ecosystem-replacements-2026-09-22.md). It introduces no provider replacement or deployment. Full drag/shift-click behavior, native anvil input, physical-client input/rendering and combined modpack/proxy acceptance require separate verification. Unrelated dedicated suites retain their earlier evidence; the broader goal remains active.
