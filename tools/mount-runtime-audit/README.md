# Mount runtime audit

Run from the repository root with PowerShell 7 and JDK 21:

```powershell
./tools/prepare-mount-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/mount-runtime-audit.gradle runServer --console=plain
```

The opt-in source directory adds the fixture only to the development server. Its marker-protected directory is `artifacts/mount-audit-server`; it binds to `127.0.0.1:25616`. Preparation preserves an existing world and verifies its server identity. The fixture uses generated NPCs, a named in-memory registry and admitted synthetic players with native protocol encoding and packet observation. It never loads production NPC/player data.

Success requires `[MOUNTAUDIT] COMPLETE 141 checks`, no `[MOUNTAUDIT] FAILED`, and successful Gradle completion. The fixture shuts down its server and removes its NPC/player sessions. Its intentional `IllegalStateException("Intentional mount listener failure")` is logged by NeoForge, caught by the fixture and followed by assertions proving that the native request scope is released and retry succeeds. It is not an unexpected server failure.

Coverage includes actual command dispatch, ID/UUID lookup, default versus named-registry scope, original option precedence, console behavior, permissions/ownership, Controllable gating, right-click events, pending cancellation, native mount/dismount cancellation and event order, preservation of the old vehicle on rejected switches, passenger-list consistency, persistence reload, self/cycle/world rejection, entity/trait removal, nested requests, direct native callback mounts, exception cleanup, canceled player effect packets, world/virtual cow and player NPC riding, and target-respawn recovery through real server ticks. Ordinary native riding outside a Citizens request is also checked.

The first retained run reproduces the original canceled-mount false-success defect. Later retained failures include fixture corrections for Brigadier rejection and the UUID owner API, and actual callback defects involving external vehicle adoption and LivingEntity's post-dismount placement. The final event-order negative control also proves that preemptive detachment loses the old mount when the new mount event is rejected. See `artifacts/mount-*.log` and the consolidated `artifacts/mount-validation-summary.json`.

Run Gradle invocations sequentially and wait for each process to finish. After opt-in audits, a clean normal test/build must exclude audit/provider fixtures from release and source jars. Physical-client seat rendering, arbitrary modded entities, the combined modpack, proxy arrival and whole mount-tree dimension transfers remain separate work.
