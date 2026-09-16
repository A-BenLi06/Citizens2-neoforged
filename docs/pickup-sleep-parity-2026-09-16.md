# Item pickup, inventory authority and sleeping-ignored player NPCs

Reference: Citizens `d98e55016c2df8102f37732cd19168fe8dd27e46`, the installed Citizens inventory implementation, and CraftBukkit `0a7bd6c81a33cfaaa2f4d2456c6b237792f38fe6` (the Paper 1.21.1 submodule revision). Target: Minecraft 1.21.1 / NeoForge 21.1.248, JDK 21. This follows implementation commit `3f4df6f` and retains the work-start baseline `f8ede13`.

## Restored behavior

Player NPCs now consume `NPC.Metadata.PICKUP_ITEMS`, disabled by default, and call native `playerTouch` for nearby entities when alive and nonspectating. The reach follows the reference player/vehicle bounding-box rules. Native item ownership, delay, stack capacity, partial pickup, arrows, XP cooldown, Mending and NeoForge cancellation remain in control. NPC entities are excluded from the scan. Equipment updates run after player physics/pickup so actual gear attributes and packets follow changes.

Mob NPCs apply `setCanPickUpLoot` at creation, during updates and immediately on command changes. `/npc pickupitems` validates input before mutation and toggles the effective disabled default, including on an unprotected NPC. Enabling pickup on a live living NPC attaches Equipment to preserve picked gear. Item-shaped NPCs have unlimited lifetime and disabled pickup/merge; the pickup event also denies their collection, including when an earlier listener requests it.

## One live inventory

The previous detached `SimpleContainer` view could overwrite pickups when edited and save stale contents. Its listeners also read back a partly written bulk update, losing later slots. The inventory menu now forwards to the entity's actual container. Player inventories, horse inventories and native Container entities share the native slot state; entities without a container use persistent stored slots. Incoming API stacks are copied, and save/copy/despawn read current contents. Equipment captures current gear on attachment, save and despawn as well as its periodic update, preserving recent Mending durability changes.

HumanController attaches Inventory before creating its entity, matching the original wrapper. Older saves with Equipment but no Inventory seed the new inventory's hand from Equipment. The legacy 72-slot stored layout is retained; player menus expose 36 slots, with Equipment restoring armor/off-hand. Hand synchronization uses the selected player slot and does not mirror a horse saddle into its main hand. Attaching Inventory to an already spawned container first captures its contents.

Menus close when their NPC despawns, is removed or reloads. An inventory whose native size has changed receives a correctly sized view on reopening. ChestMenu slots explicitly consult the container's placement policy, so padding in a horse's final row cannot accept items through clicks or quick moves and then lose them during readback. This boundary is tested using actual `ChestMenu.clicked` operations.

The block-break command's drop collector also checks `Container.canPlaceItem` before merging or placing drops. Full native slots now leave the correct remainder for world drops instead of consuming it into a padded cell. The audit exercises that production collector against the real horse inventory, checking full capacity, exact partial remainder and free-slot insertion without modifying the caller's input stack.

## Sleeping-ignored semantics

The reference HumanController calls `setSleepingIgnored(true)`. The exact CraftBukkit patch is available at [the matching Git object](https://raw.githubusercontent.com/LunaDeerMC/CraftBukkit/0a7bd6c81a33cfaaa2f4d2456c6b237792f38fe6/nms-patches/net/minecraft/server/players/SleepStatus.patch); the evidence copy is `artifacts/craftbukkit-1.21.1-sleep-status.patch`. The installed Arclight Player/CraftPlayer exposes the same fauxSleeping flag; this does not claim a complete Arclight runtime-patch audit.

The SleepStatus mixin counts player NPCs as faux sleepers within the original denominator. It requires an actual sleeper for update notifications and an actual deep sleeper before skipping night. Simply removing NPCs from the denominator would give different results at percentages below 100%. Spectator rules and native calculations without NPCs are preserved. The mixin uses wrapped operations/arguments/return values rather than replacing the full methods.

## Validation and limits

The marker-guarded fixture under `artifacts/pickup-sleep-audit-server` binds to `127.0.0.1:25588` and uses an empty registry without production data. Prepare and run:

```powershell
./tools/prepare-pickup-sleep-audit.ps1
cd neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/pickup-sleep-runtime-audit.gradle runServer --console=plain
```

Its **64 passing checks** cover native pickups, full/partial inventories, owner/delay/event denial, XP/Mending, arrow rules, current equipment snapshots, copy/despawn/respawn/file reload, item-NPC protection, mob pickup, late horse-inventory attachment, actual click/quick-move conservation, saddle/hand separation, stored-only inventories and menu cleanup. Sleep tests execute the transformed native SleepStatus with real ServerPlayer test subclasses controlling sleep flags, including percentages below 100%, spectators and the actual/deep-sleeper requirements. Evidence: `artifacts/repair-pickup-sleep-menu248.log`.

Broad regression exposed an existing movement-fixture error: `XORShiftRNG` ignores the inherited `Random.setSeed(long)`, so the purportedly deterministic 85% buoyancy assertions could fail at different points on different runs. The fixture now temporarily supplies and restores the generator's actual five-word state under its own lock for the direct impulse checks, validating both triggering and nontriggering draws. Navigation-order checks require exact scaled velocity with either permitted buoyancy outcome; separate impulse checks retain the exact power requirement. This changes only the opt-in test fixture, not the product's randomness or movement logic.

The mounted pickup branch is implemented from the reference but not separately exercised here. Physical bed/client time-skip acceptance, XP total persistence across NPC respawn, hopper/modded collector protection and full client/modpack acceptance are not established by this fixture. The protected-item assertions concern entity pickups and native merge/expiry behavior. Full Citizens/Interactions/Sentinel parity remains open; the current parity report retains the other outstanding requirements. CmdCam owns camera playback and Yuuniverse Economy owns economic APIs. No production deployment or production-data change was performed.

Full regression passes on NeoForge 21.1.248: 64 pickup/inventory/sleep checks, 81 movement/list checks, 80 text-editor checks, 285 entity-command checks, 45 dialogue-display checks, 107 NPC assertions, 223 runtime probes, 44 removal checks, 101 actual-provider/restart permission checks, 18 no-provider checks, 48 dialogue unit cases and 166 ordinary tests. Unit failures/errors/skips are zero. Pickup, general NPC regression and normal build were repeated after the final drop-collector slot check. Normal jars exclude runtime probes. Logs, completion markers and hashes are recorded in `artifacts/pickup-sleep-validation-summary.json`.
