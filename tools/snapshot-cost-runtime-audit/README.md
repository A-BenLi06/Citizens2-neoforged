# Native snapshot cost audit

Use PowerShell 7 and JDK 21 from the repository root. This reuses the isolated save-integrity server; wait for each Gradle/server process to exit before starting another audit against it.

~~~powershell
./tools/prepare-save-integrity-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/snapshot-cost-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge -I ../tools/snapshot-cost-runtime-audit.gradle runServer --console=plain
~~~

The fixture requires its marker and an empty default NPC registry. It creates 64, 128, 256 and 512 unspawned native pig NPCs in separate memory-backed registries, with synthetic unavailable-provider maps/lists and a global namespace value. Each size measures a native single-NPC save and `SimpleNPCDataStore.storeAll`, after two warmups and with three samples. Nanoseconds and current-thread heap allocation bytes are written to `artifacts/save-integrity-audit-server/snapshot-cost-results.json`. Copy the results after each run; the next run overwrites that file.

Success requires `[SNAPSHOTCOSTAUDIT] COMPLETE 8 measurements`, no fixture failure/update/event errors and successful Gradle exit. NPC names, unavailable payloads and global data must survive. Created NPCs and registries are released before shutdown. This measures in-memory serialization on a synthetic server, excluding YAML/disk writes. Timings are observations, not fixed acceptance thresholds or production-server guarantees.

Run the [save-integrity fixture](../save-integrity-runtime-audit/README.md) separately to verify failure/rollback, namespace and native normal/packet contracts. Focused transaction and record-index checks:

~~~powershell
./neoforge/gradlew.bat -p neoforge test --tests net.citizensnpcs.api.util.MemoryDataKeyTransactionTest --tests net.citizensnpcs.api.util.MemoryDataKeyCopyTest --tests net.citizensnpcs.api.npc.NPCDataStoreTest --console=plain
~~~

Finish with a normal clean test/build and verify that release/source archives exclude this fixture and keep Citizens and Interactions separate. Design and recorded results: [native batch snapshot costs](../../docs/npc-snapshot-costs-2026-10-04.md).
