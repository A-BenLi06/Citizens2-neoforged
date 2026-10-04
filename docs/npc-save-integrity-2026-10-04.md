# NPC save integrity

Implementation base: `25ff1e1`. Target: Minecraft 1.21.1, NeoForge 21.1.233 and 21.1.248.

Ordinary NPC saves caught trait and persistence errors, logged them and continued writing. A custom NPC writer could also mutate its existing store record before throwing. Batch saves published each NPC immediately, so failure in a later NPC left a mixture of old and new records. Trait removal markers were consumed before the enclosing batch had succeeded.

## Publication behavior

Native persistent saves and copy/undo snapshots now serialize into detached data using the strict trait path. Metadata, trait callbacks, delegated persistence and navigator parameters must finish before the destination changes. A retained writer key or nested collection is detached from the published snapshot. Recursive serialization of the same native NPC is rejected, and the guard is released after failure.

Persistent saves still honor `SHOULD_SAVE=false`. Copy/undo snapshots include these NPCs, their metadata and navigator parameters. The two public methods share a private implementation; overriding the snapshot API does not change the persistent-save entry point.

Trait removal intent is a set of unique removed trait paths, retained for later destinations and retries. A currently attached trait overrides its removal marker. A failed batch cannot consume an earlier NPC's removal intent, and saving to another destination cannot bring back a removed trait.

`SimpleNPCDataStore` stages the complete storage document for both single and batch saves. Unloaded definitions, unknown fields, original numeric key spelling and root-level data survive. An empty save does not create an NPC definition. Batch publication requires the same NPC object identities to remain registered; replacing an NPC with the same ID and UUID is still a different instance. Store callbacks cannot recursively flush, reload, load, delete, allocate or save through that store.

`DataKey.copyWithRoot()` preserves the enclosing document and the current key's path. `MemoryDataKey` also preserves literal key segments containing dots. This keeps namespaced persistence fields at the document root during both serialization and detached loading.

The transaction covers the native in-memory storage document. External callback mutations to live NPCs/registries and independent backend effects are not rolled back. File replacement and asynchronous YAML write ordering are separate from this change. Snapshot copying remains synchronous; large-store allocation and timing have not been measured by these fixtures.

## Validation

The bounded negative store suite reproduced five failing behaviors: partial single saves, partial batch saves, unguarded recursive callbacks, retained writer aliases and obsolete registry snapshots. The initial unbounded recursion fixture was interrupted and replaced with a bounded fixture before collecting this negative result. The expanded store suite also covers namespaced absolute access, unknown records, added/removed registry members, error recovery and stale-instance retries.

The native fixture exercises actual normal and packet NPCs, manual trait failures, delegated persistence failures, both public serialization methods, retained mutable data, repeated removal saves, reattachment, save policy and namespace loading. The registered `/citizens save` command reports failure without a success message or flush, then succeeds on retry. A trait serialization failure during default-store replacement preserves the old binding and both live transports; provisional NPCs are retired, and a later retry succeeds.

Both NeoForge versions pass all 98 dedicated checks, 196 total. Related regressions pass 156 default-store replacement checks, 386 provider-cycle checks, 305 creation checks and 62 removal checks. General validation passes 107 NPC assertions and 223 probes. All 274 ordinary tests and 100 Interactions tests pass without failures, errors or skips, and the default-version clean test/build succeeds.

All nine modified production/test/fixture checkpoints match the tested files. Release/source archives contain the implementation and exclude audit code. Citizens contains no Interactions classes, resources or embedded JAR; Interactions contains no Citizens classes and its entry names and contents match the preceding release. The original Citizens save is unchanged. Source, log, XML and archive evidence is consolidated in `artifacts/save-integrity-validation-summary.json` by `artifacts/summarize-save-integrity.ps1`. Reproduction instructions are in [the native fixture](../tools/save-integrity-runtime-audit/README.md).

## Ownership and remaining work

This is Citizens persistence work. Interactions conversations, choices, conditions, actions, cutscenes and player progress remain in the independent Interactions source set and NeoForge JAR. Dependency remains Interactions → Citizens.

Atomic/ordered YAML writes, persistence delegate initialization failures, large-store snapshot costs, configuration/shop reload and broader registry/provider/client acceptance remain on [the current checklist](remaining-citizens-work-2026-09-26.md). No production deployment or physical-client acceptance is implied.
