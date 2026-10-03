# Native NPC datastore audit

Run from the repository root with PowerShell 7 and JDK 21. Every server process must exit before the next starts:

~~~powershell
./tools/prepare-datastore-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' '-PdatastorePhase=absent' -I ../tools/datastore-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' '-PdatastorePhase=present' -I ../tools/datastore-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' '-PdatastorePhase=absent-again' -I ../tools/datastore-runtime-audit.gradle runServer --console=plain
~~~

Repeat the three commands without the version override for default NeoForge 21.1.233. The fixture uses `artifacts/datastore-audit-server`, loopback port 25631 and world `datastore-world`. It requires an empty default NPC registry. Its retained `provider-state.yml` and phase marker verify transition order. To repeat the sequence, start with `absent` and reuse the definitions.

Only the present phase registers `citizens_datastore_audit:animal`, a real Cow-based entity with native attributes. The saved NPC must remain intact while absent, load and spawn with its exact UUID when present, and survive another absence without losing opaque data. Rejected neighboring records, colliding IDs/UUIDs, creation vetoes and automatic allocation must preserve that recovery source.

The default-store checks use Citizens' actual command dispatcher, normal and packet NPCs, invalid save files and real respawn ticks. Synthetic YAML/UUID failures and a canceled creation intentionally log diagnostics. Success requires `[DATASTOREAUDIT] COMPLETE ... phase=...`, no `[DATASTOREAUDIT] FAILED`, and successful Gradle completion. Temporary NPCs/registries and the forced chunk are released before shutdown.

Native results are 64/66/64 checks on 21.1.248 and 63/66/64 on 21.1.233. The extra first-run check verifies initialization of the synthetic provider file. The focused ordinary suite is:

~~~powershell
./neoforge/gradlew.bat -p neoforge test --tests net.citizensnpcs.api.npc.NPCDataStoreTest --tests net.citizensnpcs.api.util.YamlStorageRoundTripTest --console=plain
~~~

Its 17 tests include ID boundaries, detached callback data, retained snapshots, isolated failures, ambiguous keys and YAML aliases/duplicates. Final acceptance also passes 305 creation checks, 62 removal checks, 107 general NPC assertions, 223 probes, 261 ordinary tests and 100 Interactions tests. The clean test/build and archive ownership/exclusion checks pass. Consolidated evidence: `artifacts/datastore-validation-summary.json`.
