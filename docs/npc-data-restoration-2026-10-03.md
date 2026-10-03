# NPC identity allocation and failed reloads

Implementation base: `86ac135`. Target: Minecraft 1.21.1, NeoForge 21.1.233 and 21.1.248.

Creating an NPC could reuse an ID belonging to a saved definition that had not loaded, including an NPC whose entity mod was absent. A malformed UUID or canceled creation could stop restoration of later records, and creation cleanup could delete the definition being restored. Global reload removed live NPCs before discovering an unreadable source file.

## Identity allocation and restoration

Both native stores allocate nonnegative integer IDs against the current registry. The persistent store also reserves every parseable stored ID, including malformed records, unavailable types and ambiguous spellings. Invalid allocation hints cannot override reserved identities. At the integer boundary the allocator finds an available ID without overflow, continuing beyond its last issued cursor when possible. Explicit ID creation retains the API's existing overwrite semantics.

Records restore in ID order with recoverable failures isolated. Invalid UUIDs, missing names/trait lists, missing providers and creation/load failures retain their source for a later retry. A duplicate UUID cannot replace an NPC already restored. Multiple keys identifying the same integer, such as `01` and `1`, remain unloaded. A unique spelling such as `007` retains that key through saving and explicit removal.

Each loader receives detached data. The accepted legacy type is normalized in that copy before traits load, so a loose name such as `Zombie Villager` produces the matched native entity instead of falling back to a player. Defaults and loader edits do not change the saved source during restoration.

Failed creation/load cleanup retires the runtime candidate without clearing its recovery source. Publishing a partial record or recursively reloading the source during restoration is rejected. Ordinary explicit removal after restoration still clears the record. Unknown fields remain in the existing source branch when an ordinary save merges the loaded state.

## Failed source reads

`YamlStorage.load` prepares the complete replacement before changing its snapshot. Non-mapping roots, duplicate keys, collisions after converting keys to strings and recursive collection aliases are rejected. Shared nonrecursive aliases remain supported as independent values. An intentionally empty document remains a valid empty snapshot.

`SimpleNPCDataStore.reloadFromSource` validates the NPC section and restores its previous in-memory snapshot if the source reports failure, throws, or contains an invalid section. The `NPCDataStore` contract now explicitly requires failed reads to preserve the prior snapshot and report failure through an exception.

Citizens reads the NPC source before closing editors, clearing pending respawns or removing NPCs. Failed reads leave normal and packet NPCs alive with the same registry and entity identities. `/citizens reload` propagates failure. After correction, a successful reload replaces instances and restores their original identities, types and transport.

## Validation

The initial focused run reproduced 11 failures among 17 tests. All 17 then passed. The memory allocator's integer-boundary expectation was clarified to continue its previous allocation cursor rather than require the lowest unused ID; both policies avoid collisions, and the final choice is documented above.

The [native fixture](../tools/datastore-runtime-audit/README.md) uses an actual registered entity type and separate JVM runs against the same definitions:

| NeoForge | Provider absent | Provider present | Provider absent again |
|---|---:|---:|---:|
| 21.1.248 | 64 | 66 | 64 |
| 21.1.233 | 63 | 66 | 64 |

All 387 checks pass. The extra first-run check verifies initialization of the synthetic source file. The present phase spawns the recovered NPC as the real mod entity with its original UUID. Both absent phases verify complete retained definitions, including opaque nested fields. Other checks cover native creation vetoes, explicit removal, identity collisions, type aliases, failed global reloads and successful real-tick respawns of normal and virtual NPCs.

Malformed YAML, invalid UUIDs and canceled creation intentionally produce diagnostics. Acceptance requires the completion marker, no fixture failure marker and successful Gradle exit. The existing creation regression also deliberately throws one creation-listener and one removal-listener exception; the consolidated verifier requires exactly those two messages in that suite and rejects other event errors.

Related regressions pass 305 creation and 62 removal checks. General validation passes 107 NPC assertions and 223 probes. All 261 ordinary tests and 100 Interactions tests pass without failures, errors or skips; the default-version clean test/build succeeds. All 14 production/fixture inputs match the tested source checkpoint. Release/source archives include the implementation and exclude audits. Interactions contains no Citizens classes, Citizens contains no Interactions implementation, and Interactions entry names/contents match the preceding release. The original Citizens save is unchanged. Detailed logs, reports, provider-state snapshots and source/archive hashes are consolidated in `artifacts/datastore-validation-summary.json` by `artifacts/summarize-datastore.ps1`.

## Boundaries and remaining work

This is Citizens-owned NPC persistence work. Interactions retains its independent source set, mod ID, dialogue implementation and NeoForge JAR, with its one-way Citizens dependency. The fixture changes no original server data or installed modpack.

Custom stores must honor the source-read failure contract. This follow-up does not establish transactional handling of every trait callback or backend. At this checkpoint the default-store setter still left the active registry bound to its previous store; that issue is addressed by the subsequent [store replacement follow-up](npc-store-replacement-2026-10-03.md). Configuration/shop reload failures and broader registry shutdown/replacement lifecycles retain targeted acceptance work. Wider service/data/provider and physical-client/modpack/proxy requirements remain on the [current checklist](remaining-citizens-work-2026-09-26.md).
