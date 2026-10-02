# Native NPC death and respawn

Accepted native death previously left the controller holding a dead entity. A later spawn therefore refused to create a replacement. Citizens' `RESPAWN_DELAY` had no reader, and Sentinel's respawn counter was in a trait branch that normal NPC ticks never reached.

Follow-ups: [armor-stand destruction](armorstand-death-2026-10-02.md) addresses complete native breaking/drops, and [dragon death phases](dragon-death-2026-10-02.md) handles the native flight and longer death sequence. These supersede the implementation limitations recorded below; client/modpack acceptance remains open.

## Lifecycle and drops

The native `LivingEntity.die` and `ServerPlayer.die` boundaries now distinguish accepted death from NeoForge cancellation. The entity remains under native death processing while Citizens offers the mutable `NPCDeathEvent` drop list once. `DROPS_ITEMS` defaults to suppressing native drops; trait and listener additions remain available, and native drop cancellation still decides whether items enter the level. Deaths that skip loot still notify the lifecycle without creating loot.

After native processing, only the originating controller is detached. World entities retain their native death animation; virtual NPCs send the death event to existing viewers and retire interaction lookup immediately. The detached visual is removed after 20 ticks or before a replacement spawn reuses its profile/UUID. It cannot acquire new world viewers. Player NPCs mark their death state only after native cancellation has been resolved.

Native `LivingDropsEvent` notification also remains available when an entity's special destruction method bypasses those wrappers. An armor-stand explosion regression exposed an omitted notification in the recovered candidate. A scoped drop notification now preserves `NPCDeathEvent`, trait additions and native cancellation without treating that event alone as the whole special destruction method. Special methods still need their own controller/visual lifecycle work: armor stands directly remove themselves, and the dragon's death animation lasts 200 ticks. These are explicit open requirements, not covered by the ordinary mob/player completion claim.

## Respawn ownership

Citizens schedules `RESPAWN_DELAY + 20` ticks, reads the current Home location at the deadline and otherwise uses the death location. Sentinel retains the original negative/zero/positive meanings: destroy one tick later, stay registered without respawning, or respawn on the original ten-tick cadence. The retained Sentinel bytecode checks its counter before incrementing it: delays 1/10 run at tick 20, 11/20 at tick 30, and 21 at tick 40. An explicit Sentinel spawn point remains distinct from its fallback chase origin.

These schedules run on the server scheduler independently of spawned-trait ticks. An accepted replacement spawn retires the remaining timers. A rejected spawn leaves a later timer available. Manual despawn, controller replacement, removal and registry reload invalidate obsolete death work. Callbacks that destroy or replace an NPC cannot make the older handler detach or respawn the replacement.

Spawn and despawn operations retain their exact candidate and trait snapshot across callbacks. A newer spawn owns its completion callback and metadata. Teleports requested before spawn initialization finishes retain their approved destination; native admission callbacks that discard a candidate cannot leave it indexed in the level. This also preserves mounts, holograms and scoreboard cleanup through the shared lifecycle.

## Validation

The isolated fixture and reproduction commands are in [the runtime audit](../tools/death-runtime-audit/README.md). Its expanded NeoForge 21.1.248 run passes 818 checks through actual native damage/death, events, packet encoders and server ticks. Coverage includes both registries, mob/player and world/virtual NPCs, cancellation followed by a later death, mutable/default/custom drops, exact visual and respawn deadlines, coexisting timers, canceled spawn retries, manual invalidation, recursive death callbacks and destruction/replacement/teleport during four spawn phases. A vanilla mob control retains native death behavior.

After restoring the special drop notification, the final fixture passes 839 checks on each of NeoForge 21.1.233 and 21.1.248. Eight related suites pass 1,688 checks: mount transfer 216, hologram transfer 822, scoreboard 312, packet viewers 43, world visibility 47, tracking admission 108, packet interactions 78 and removal 62. These eight runs precede the narrow special-drop repair; both dedicated versions and the general runtime suite ran again after it.

Final general validation passes 107 NPC assertions and 223 runtime probes. All 232 ordinary tests and the clean build pass on the default NeoForge 21.1.233. Binary/source archives contain the new production hooks and exclude audit fixtures. Citizens contains no Interactions classes or Mixins; the separate Interactions archive has exactly the same entry names and contents as the preceding release. The original Citizens save hash is unchanged. No production deployment occurred.

The earlier `artifacts/death-runtime248-01.log` records the recovered candidate's virtual destruction failure; `death-runtime248-02.log` and the fresh `death-recovered248.log` pass the earlier 454-check inventory. `death-special-drops-negative248.log` reproduces the armor-stand notification regression before its repair. These are separate from the final runs. Final regression, build, source checkpoint and archive results are recorded in `artifacts/death-validation-summary.json` and the root walkthrough. Conditional checks of randomly generated native loot can change the printed check total between runs; every required scenario still executes.

References are the checked-in original Citizens `EventListen.onEntityDeath`, retained Sentinel 2.9.0 bytecode and the native 1.21.1 death implementations. The timer's bytecode is retained as `artifacts/death-legacy-sentinel-timer.txt`. Special native sources are retained as `artifacts/death-native-ArmorStand.java` and `artifacts/death-native-EnderDragon.java`.

This change belongs to Citizens. Interactions retains its own source set, mod ID, configuration and JAR; its dialogue implementation is unchanged. Physical-client death animation, arbitrary mod/entity death overrides and combined modpack acceptance remain unverified. The command, data and integration requirements in [the current checklist](remaining-citizens-work-2026-09-26.md) remain open.
