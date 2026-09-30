# Native swap-hand dialogue audit

Run from the repository root with PowerShell 7 and JDK 21:

```powershell
./tools/prepare-swap-hand-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/swap-hand-runtime-audit.gradle runServer --console=plain
```

The opt-in fixture uses `artifacts/swap-hand-audit-server`, bound to `127.0.0.1:25621`, with world `swap-hand-world`. Preparation rejects an occupied port and validates the existing fixture identity. The fixture creates its own memory NPC registry, conversations, registered dialogue controller, admitted synthetic native players, and outbound packet encoders. Input enters `ServerGamePacketListenerImpl.handlePlayerAction` as `SWAP_ITEM_WITH_OFFHAND`; the actual NeoForge hand event and native inventory assignment determine the outcome.

The final fixture passes 139 checks. Success requires `[SWAPHANDAUDIT] COMPLETE`, no `[SWAPHANDAUDIT] FAILED`, and successful Gradle completion. Coverage includes every legacy input mode, sneaking, case-insensitive settings, NPC id/name precedence, active-session behavior, permission/cooldown/air/radius gates, native look-packet timing and eye-ray occlusion, nearest world and packet NPCs, hidden/unpaired viewers, redirect targets/cycles, spectator/current-player admission, native listener cancellation/modified stacks, reload, callback invalidation and reentry. It verifies successful admission preserves hands and refused admission retains native swapping. Native teleport acknowledgement lets look packets precede the head-rotation tick. Late visibility callbacks add a wall or move an occluder after the earlier ray; stale selection must be refused. Generated players, NPCs, temporary blocks and the forced chunk are cleaned up before shutdown.

Run Gradle/server invocations sequentially and wait for termination. The fixture is excluded from ordinary builds. A physical client and the full production modpack remain separate acceptance checks.
