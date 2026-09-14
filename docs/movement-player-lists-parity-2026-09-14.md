# Swimming, player movement and player-list defaults

Implementation follow-up for 2026-09-14 (UTC+08:00), after `4205b78`. Reference: Citizens2 `d98e55016c2df8102f37732cd19168fe8dd27e46`, including the native bridge behavior.

## Swimming and movement

`/npc swim` previously persisted a value without a runtime consumer. The new native swimming update implements the reference behavior:

- Aquatic NPCs with Minecraft AI disabled use the reference enabled default. Explicit metadata overrides the default; toggling before spawn resolves the native entity type and toggles its effective value. Invalid booleans/unknown flags fail before mutation.
- While navigating in liquid blocks, velocity uses `water-speed-modifier` or `npc.movement.water-speed-modifier` (default **1.15**). An unknown/upward destination permits buoyancy; level/downward destinations retain their requested direction.
- Idle buoyancy respects the Gravity trait. The native impulse applies in water, with the reference 85% opportunity and **0.02** aquatic / **0.04** other-entity power. This does not suppress Minecraft AI's own swimming or replace the entity's animation pose. The reference's liquid-block and water-only impulse boundaries remain.
- Native Citizens navigation writes velocity directly, so the water adjustment runs after that write. A controlled navigation strategy verifies that it cannot overwrite the configured multiplier.

Player NPCs previously received `ServerPlayer.tick` without the base/physics work that real players receive through their network connection. Clientless player NPCs now run base state and native movement themselves. Default player NPCs retain their existing absence of automatic hunger/healing; food ticking follows the explicit Minecraft-AI flag. Native walking to a real navigation target and rising through a water basin are verified.

Player and horse-family NPCs now initialize their base step-height attribute to **one block**, unless AttributeTrait explicitly supplies that attribute. Custom values and respawns retain the override. The fixture checks actual collision movement over a one-block obstacle, not only the stored number.

## Two independent lists

| Setting / metadata | Meaning and implementation |
|---|---|
| `npc.player.remove-from-list` / `removefromplayerlist` | Controls the native world's Java player collection and the matching ChunkMap player/ticket index. Default is true. `/npc playerlist -a/-r` now applies this reference behavior. |
| `npc.tablist.disable` / `removefromtablist` | Controls the client Tab-list entry independently. Default is true. Global defaults apply when the NPC has no explicit override. |

This distinction matters: the old port used the player-list command to send a Tab update and did not update the native player collection. Its update packet also used Minecraft's constructor, which hardcodes `listed=true`, so a requested hide could show the NPC instead. The tracker now creates explicit immutable Entry records before publishing packets. Hidden entries retain the profile required to render the skin and are removed on untracking/despawn. Delayed refreshes read current policy and cannot undo a later explicit show.

Profile data still precedes player-entity spawn packets. Mirrored refreshes use NeoForge's public `getPlayersWatching` API and preserve each actual viewer's own profile/name. They no longer send profiles to every player in the dimension, where an untracked client would never receive matching cleanup.

World-list membership is applied on spawn and checked against actual membership, so a later tracking callback cannot silently reintroduce an excluded NPC. Index changes occur only when membership changes; included player NPCs also update their index when moving between sections. Real players are untouched. NPCs continue ticking in loaded/ticking chunks. The existing NPC chunk-loading and natural-spawning options still act as additional gates for NPCs included in the player index; explicit chunk tickets remain the way to keep an otherwise unloaded NPC active.

## Evidence and remaining scope

The isolated fixture passes **80 checks** in `artifacts/repair-movement-lists-runtime248-lifecycle.log`. It uses native fluid state, actual collision movement, a completed player navigation, real entity tracking, explicit chunk-receipt acknowledgements, outgoing profile/spawn packets and a wire-codec round trip. It checks the two defaults, both directions of metadata overrides, command effects, hidden/visible state, mirrored recipients, pending refreshes, membership restoration, movement between sections, despawn and respawn. Default health/food behavior is checked over more than 100 server ticks.

The older general regression used `@e[type=player]` to check one spawned skin NPC. That optimized selector reads the player collection, so it correctly omits an NPC removed from that collection. `SkinSpawnRuntimeAudit` now checks the same fixture bounds through the native entity index, requiring the NPC name, player subclass, live state and exact indexed identity. The 107-assertion regression retains its original spawn requirement.

Prepare with `./tools/prepare-movement-list-audit.ps1`, then from `neoforge` run:

```powershell
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/movement-list-runtime-audit.gradle runServer --console=plain
```

The marker-guarded fixture is under `artifacts/movement-list-audit-server`, binds to `127.0.0.1:25587` and contains no production NPC/player data. Use JDK 21. Regression passes 285 entity-command checks, 80 text-editor checks, 45 dialogue-display checks, 107 NPC assertions, 223 runtime probes, 44 removal checks, 101 actual-provider/restart permission checks, 18 no-provider checks, 48 dialogue unit cases and 166 ordinary tests. Unit failures/errors/skips are zero. Movement/NPC/build gates were repeated after the final membership lifecycle repair; normal jars exclude runtime probes. Evidence, scope and hashes are in `artifacts/movement-list-validation-summary.json`.

Physical-client skin rendering, custom mod fluid/movement behavior and other player-specific behavior (including opt-in item pickup and the original sleep-ignore semantics) remain separate acceptance/implementation work. This does not establish full NPC physics, Sentinel or API parity. Other documented dialogue, quest/item, group-provider and client/modpack gaps remain open. CmdCam still owns camera playback; Yuuniverse Economy still owns economic APIs. No production deployment or configuration migration was performed.
