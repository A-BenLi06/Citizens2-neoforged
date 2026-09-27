# Parent-dimension hologram runtime audit

Run from the repository root with PowerShell 7 and JDK 21:

```powershell
./tools/prepare-hologram-transfer-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/hologram-transfer-runtime-audit.gradle runServer --console=plain
```

The opt-in fixture uses `artifacts/hologram-transfer-audit-server`, bound to `127.0.0.1:25618`, with world `hologram-transfer-world` and marker `hologram-transfer-audit-fixture.txt`. Preparation validates an existing fixture's server identity and preserves its world. The audit uses generated NPCs and natively admitted embedded player connections in the overworld/nether; it does not read production NPC or player data. Cleanup removes those NPCs/sessions, restores the fixture's packet-hologram setting and shuts down the server.

Success requires `[HOLOGRAMTRANSFERAUDIT] COMPLETE 822 checks`, no `[HOLOGRAMTRANSFERAUDIT] FAILED`, and successful Gradle completion. `artifacts/hologram-transfer-negative248.log` reproduces the original `outbound_0_dimension_0` failure. The smaller 553/819-check runs remain available; `artifacts/hologram-transfer-final248.log` is the final dedicated gate.

Coverage includes all built-in renderers and name helpers; ordinary/virtual parent and helper combinations; real native packet encoding; helper identity, native indexes, positions, old/new viewer membership, individual personalized metadata and mount order; source-state retention and retry after teleport/admission/dismount refusal; hidden helpers while their parent is in another world; and callback destruction, removal, clear/replacement, reentry and newer teleport ownership. It also covers stale trait references and replacement lines created during removal callbacks.

Run Gradle invocations sequentially, waiting for each process to finish. Relevant regressions are mount-transfer, packet-hologram, hologram-metadata, interaction-label, item-hologram, packet-viewer, world-visibility, text-editor, removal and the general runtime fixture, followed by a clean ordinary `test build`. Verify release/source archives exclude opt-in audit/provider code and that the retained Interactions archive entry contents and original save hash remain unchanged.

See [the implementation record](../../docs/hologram-parent-transfer-2026-09-27.md) and `artifacts/hologram-transfer-validation-summary.json`. Physical-client and full provider/modpack/proxy acceptance remain separate work.
