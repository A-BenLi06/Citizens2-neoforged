# Spawn, player teleport and NPC relocation commands

The native command implementation omitted several original options. Spawn only resolved integer IDs and did not authorize the resolved target. Player teleport always used the exact location. NPC relocation ignored its center flag and omitted cursor/front modes and cross-world authorization. Immediate feedback could also report success after cancellation or while a mounted transfer was still pending.

## Command behavior

- `/npc spawn (id|uuid|name) -l` uses the existing selector and duplicate-name prompt. Quoted names and case-insensitive names work. It uses the stored location unless an explicit `--location` or `--entitylocation` overrides it; a missing stored location uses the native command context. Successful completion updates spawned state and selection. Canceled attempts do not publish success or set spawned state in advance. A newer callback selection or changed owner is preserved.
- `/npc tp` and `teleport` place the player in front of the selected NPC, facing back toward it. `-e` preserves exact position/yaw/pitch. Front placement follows the original horizontal projection of the direction, including its pitch-dependent horizontal distance. Both spawned and stored locations work.
- `/npc tphere`, `tph` and `move` support `cursor`, `-c` and `-f`. Cursor uses a native 64-block outline/fluid ray from the player's actual view, places the NPC above the hit block and rejects a miss. Centering uses block coordinates plus half a block in X/Z and resets block-location orientation; front placement applies afterward, matching the original option order. Console/native positioned command sources and explicit location flags retain their context.
- Cross-world NPC relocation requires `citizens.npc.tphere.multiworld` based on the actual requested destination. An unspawned target receives the cancellable teleport event before spawning and uses its resulting destination. Feedback includes the actual completed location.

Spawn and explicit despawn authorize the target they resolve, including after a chat choice. A replaced candidate cannot reuse a pending prompt even if its ID and UUID match. Global `--id`/`--uuid` overrides now require selection permission (or administrative authority) and fail instead of silently acting on the current selection. Invalid IDs, UUIDs and location values produce command errors before mutation.

## Completion and command sources

The additive teleport callback overload reports confirmed completion in Citizens, including native destination admission and passenger remounting. Deferred requests retain their own callback. Cancellation, rejected admission, rollback and superseding requests do not notify. Existing custom implementations keep their old void teleport behavior through the default overload and can override it to report completion; no successful result is invented for a legacy implementation.

Source validation distinguishes current registered NPC entities and native fake players from human player connections. Live NPC/automation command executors retain their existing permission context, while removed/replaced NPC sources and retired player connections cannot continue a delayed operation. Player-shaped NPC senders use their NPC controller for teleporting so virtual entities keep their transport.

## Verification

The dedicated [native fixture](../tools/relocation-runtime-audit/README.md) exercises the real dispatcher and lifecycle. Its early runs passed 222, 246 and 255 checks as coverage expanded. `relocation-id-permission-negative248.log` reproduced unauthorized ID fallback; `relocation-source-negative248.log` reproduced reuse of a retired NPC source. The first compile diagnostics were a missing production import and a fixture superclass name collision; both are retained separately from passing runs.

The NPC-source follow-up also corrected a fixture assertion that used the logical NPC UUID for a native player entity index; player profiles can use a different Minecraft UUID. The final assertion uses the actual entity UUID. No production identity mapping was changed.

Final version, regression, unit-test/build, source checkpoint and archive results are recorded in `artifacts/relocation-validation-summary.json` and the root walkthrough. Interactions remains its own unchanged mod/JAR. The remaining command groups, data/integration requirements and physical-client/modpack/proxy acceptance remain on [the current checklist](remaining-citizens-work-2026-09-26.md).

Final dedicated runs pass 302 checks each on NeoForge 21.1.233 and 21.1.248. Related runs pass 216 mount-transfer, 129 follow, 1,639 death, 62 removal and 49 command-location checks. Those broad runs precede the narrow native-command-source follow-up; dedicated checks on both versions and the general suite ran again afterward. Final general validation passes 107 NPC assertions and 223 probes; all 232 ordinary tests and clean build pass on 21.1.233. Release/source archives exclude audit fixtures and preserve the separate Interactions archive's entry contents. The original Citizens save is unchanged; no production deployment occurred.
