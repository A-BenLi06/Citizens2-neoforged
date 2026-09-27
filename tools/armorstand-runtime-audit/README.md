# Armor stand pose runtime audit

Run from the repository root with PowerShell 7 and JDK 21:

```powershell
./tools/prepare-armorstand-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/armorstand-runtime-audit.gradle runServer --console=plain
```

The opt-in fixture uses `artifacts/armorstand-audit-server`, bound to `127.0.0.1:25619`, with world `armorstand-world` and marker `armorstand-audit-fixture.txt`. Preparation validates existing server identity and preserves the fixture world. A fixture-only permission provider checks both permitted and denied player commands. Generated NPCs and the embedded native player session are removed before shutdown. Production data is not read or modified.

Success requires `[ARMORSTANDAUDIT] COMPLETE 401 checks`, no `[ARMORSTANDAUDIT] FAILED`, and successful Gradle completion. The negative run in `artifacts/armorstand-negative248.log` fails at `immediate_head`; the final gate is `artifacts/armorstand-final248.log`.

Coverage includes all six command parameters, combined quoted triples, original angle units, native normalization/metadata, actual entity ticks, malformed count/empty/nonfinite/overflow rejection without partial changes, strict switches, permissions/ownership/type/selection, immediate API changes, external pose preservation, snapshot/despawn capture, legacy radian fields, respawn, never-spawned and virtual NPCs, retired trait references and native packet encoding. Physical-client pose drawing remains separate acceptance.

Run Gradle/server invocations sequentially and wait for termination. Related regression fixtures are hologram-transfer, packet-hologram, hologram-metadata and item-hologram, followed by the general runtime audit and a clean ordinary `test build`. Verify that release/source jars exclude all audit/provider fixtures and that Interactions archive contents and the original save hash remain unchanged.
