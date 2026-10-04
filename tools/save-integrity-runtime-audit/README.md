# Native NPC save-integrity audit

Run from the repository root with PowerShell 7 and JDK 21. Wait for each Gradle/server invocation to exit before starting the next:

~~~powershell
./tools/prepare-save-integrity-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/save-integrity-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge -I ../tools/save-integrity-runtime-audit.gradle runServer --console=plain
~~~

The second run uses default NeoForge 21.1.233. The fixture is `artifacts/save-integrity-audit-server`, loopback port 25633 and world `save-integrity-world`. It requires an empty default registry, creates synthetic NPCs and memory stores, restores the original default binding and releases forced chunks before shutdown.

Actual normal and packet NPCs exercise trait/delegate serialization errors, retry, namespace paths, retained keys/collections, removal intent across destinations, reattachment, save policy, navigator state and copy loading. Batch checks preserve unknown/unloaded definitions and original numeric spelling. Recursive storage mutation and replacement of an NPC with the same ID/UUID must not publish an obsolete snapshot. The registered save command and default-store setter are exercised with a failing native trait and a successful retry.

Success requires `[SAVEINTEGRITYAUDIT] COMPLETE 114 checks`, no fixture failure/update/event errors and successful Gradle exit. Delegate initialization adds six checks to the original 98-check fixture. Atomic YAML writing adds ten more: normal/packet NPCs persist through a real file, failed serialization retains the prior bytes, temporary files are cleaned and retry restores both NPCs with their identities and unknown data. The intentional registered-command failure prints the synthetic trait-save exception; it must produce neither a success message nor a disk flush. Do not treat the earlier fixture-shape failures as acceptance runs.

Focused tests:

~~~powershell
./neoforge/gradlew.bat -p neoforge test --tests net.citizensnpcs.api.npc.NPCDataStoreTest --tests net.citizensnpcs.api.util.MemoryDataKeyCopyTest --console=plain
./neoforge/gradlew.bat -p neoforge test --tests net.citizensnpcs.api.persistence.PersistenceDelegateFailureTest --console=plain
./neoforge/gradlew.bat -p neoforge test --tests net.citizensnpcs.api.util.YamlStorageWriteTest --tests net.citizensnpcs.api.util.YamlStorageRoundTripTest --console=plain
~~~

Related regression coverage includes store replacement, the unavailable/present/unavailable provider cycle, creation, removal, general NPC probes, ordinary tests and Interactions tests. Finish with a normal clean test/build so the release archives exclude fixture classes. Verify that Citizens and Interactions keep separate contents. Details: [save-integrity follow-up](../../docs/npc-save-integrity-2026-10-04.md) and [YAML-write follow-up](../../docs/yaml-storage-writes-2026-10-04.md).
