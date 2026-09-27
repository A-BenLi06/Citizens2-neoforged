# Mounted passenger-tree runtime audit

Run from the repository root with PowerShell 7 and JDK 21:

```powershell
./tools/prepare-mount-transfer-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/mount-transfer-runtime-audit.gradle runServer --console=plain
```

The opt-in source set uses `artifacts/mount-transfer-audit-server`, bound to `127.0.0.1:25617`, with the `mount-transfer-world` world and `mount-transfer-audit-fixture.txt` marker. Preparation validates the existing server identity and preserves the fixture world. Generated NPCs and embedded, natively admitted player connections are removed during cleanup; the server shuts itself down. No production data is loaded.

Success requires `[MOUNTTRANSFERAUDIT] COMPLETE 216 checks`, no `[MOUNTTRANSFERAUDIT] FAILED`, and successful Gradle completion. One intentional `IllegalStateException("expected admission failure")` is logged by NeoForge, caught by the fixture and followed by recovery assertions. It is not an unexpected server failure.

The fixture covers full sibling/nested trees; world/virtual entity and player NPCs; UUID/health/controller/index retention; ordinary boat roots and actual ServerPlayer sessions; same-world and cross-dimension round trips; teleport/dismount cancellation and partially detached trees; rejected admission and rollback; native join exceptions, destruction and newer return teleports; explicit and direct mount changes during detachment; sibling restoration; native passenger order and actual-tick retries without a saved MountTrait; and removal/new teleports that supersede pending retries.

Retained logs include `mount-transfer-negative248.log` (the original leaf-only transfer), `mount-transfer-partial-negative248.log` (lost detached sibling), `mount-transfer-callback248.log` and `mount-transfer-admission-diagnostic248.log` (destroyed candidate still in the destination index), and `mount-transfer-final248.log` (216 passing checks). Intermediate successful logs cover smaller inventories and are not the final gate.

Run Gradle/server invocations sequentially and wait for their process handles to finish. Run the mount, follow, packet-hologram, packet-viewer, movement-list, removal and general runtime regressions, then a clean ordinary `test build`. Verify that audit/provider classes are absent from all release/source jars. Consolidated evidence is in `artifacts/mount-transfer-validation-summary.json`.

Physical-client rendering and input, the target modpack and proxy arrival remain separate acceptance. The built-in NPC coordinator does not claim support for arbitrary external NPC controllers or all possible direct mod teleports. See [the implementation record](../../docs/mounted-tree-transfer-2026-09-27.md).
