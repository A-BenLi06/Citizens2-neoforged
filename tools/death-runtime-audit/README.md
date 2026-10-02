# Native death and respawn runtime audit

Run from the repository root with PowerShell 7 and JDK 21:

```powershell
./tools/prepare-death-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/death-runtime-audit.gradle runServer --console=plain
```

The opt-in fixture uses `artifacts/death-audit-server`, `127.0.0.1:25622`, world `death-world`, and marker `death-audit-fixture.txt`. Preparation validates existing server identity and preserves its world. The audit refuses a nonempty default registry, creates a named memory registry, and removes its NPCs, native drops, embedded player connection, and forced chunk before shutdown. Production NPC data is never read or copied.

Success requires `[DEATHAUDIT] COMPLETE`, no `[DEATHAUDIT] FAILED`, and successful Gradle completion. The count is printed by the fixture. Run Gradle/server invocations sequentially and wait for termination. Do not combine this init script with other audit init scripts or include its sources in release artifacts.

Coverage exercises native `hurt` and `die` through NeoForge events for mob, player, packet mob, and packet player NPCs in both registries. It checks cancellation and later successful death; exact-once mutable drop notification; default suppression, enabled native equipment/inventory drops, `DropsTrait` additions, listener replacement, and canceled native drops; immediate entity release; packet interaction retirement, native death packets, and removal at 20 ticks. Timed checks cover Citizens delay plus 20 ticks with a changed home; Sentinel negative destruction after one tick, zero staying despawned, and positive ten-tick quantization; both timers coexisting, first packet spawn rejection preserving the later timer, manual despawn invalidation, recursive death callbacks, and replacement/destruction during lifecycle callbacks. A vanilla mob control verifies the native path remains independent of Citizens.

Checks use real server ticks after the Citizens scheduler runs. They do not invoke the death coordinator or advance the scheduler directly. Client rendering of the death animation remains separate visual acceptance.

The expanded fixture also exercises a manual spawn after accepted death, with destruction, replacement and teleport callbacks at trait pre-spawn, native level admission, `NPCSpawnEvent` and trait spawn. Both mob/player and world/virtual candidates are covered (virtual candidates intentionally have no native admission event). It verifies native indexes, destination retention, spawn metadata cleanup, completion callback ownership and the later obsolete timer. The expanded NeoForge 21.1.248 run passes 818 checks in `artifacts/death-callbacks248.log`.

The final fixture adds native armor-stand explosion drops, trait additions and canceled native drops for world/virtual NPCs. `artifacts/death-special-drops-negative248.log` reproduces the missing `NPCDeathEvent` before the repair. Final runs are `artifacts/death-final248.log` and `artifacts/death-final233.log`; omit the NeoForge property override to exercise the project's default 21.1.233. Native random loot can vary the number of conditional per-item checks. This covers special drop notification only: armor-stand direct removal and the dragon's distinct death animation still need their own lifecycle work.

The subsequent [armor-stand destruction follow-up](../../docs/armorstand-death-2026-10-02.md) adds controller release, complete native equipment/stand drops, survival hit timing, explosion/fire, creative/bypass/direct no-loot kills, recursive damage, callback replacement/destruction, parent block capture, gamerule behavior, virtual removal packets and respawn deadlines. `artifacts/armorstand-death-negative248.log` reproduces the original retained-controller failure. Final runs `armorstand-death-final248.log` and `armorstand-death-final233.log` each pass 1,639 checks. The earlier 839-check logs above are historical; dragon lifecycle work remains open.
