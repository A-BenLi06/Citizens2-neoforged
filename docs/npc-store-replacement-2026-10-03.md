# Default NPC store replacement

Implementation base: `92c784e`. Target: Minecraft 1.21.1, NeoForge 21.1.233 and 21.1.248.

The setter changed Citizens' store field while its registry continued allocating IDs, deleting records and saving through the previous store. It did not load the replacement. A store selected before the server-starting hook was also overwritten by the default YAML store.

## Replacement contract

An explicit startup override is retained and loaded once levels are ready. A running server loads a provisional registry through the new store, saves the previous registry, and publishes the new store and registry together. The previous runtime instances are then retired without deleting their persisted definitions. Assigning the current store is a no-op; null is rejected.

Before publication, global lookups still expose the previous default registry. Providers must populate the registry passed to `loadInto` synchronously. Native `NPC.load` schedules respawns for the following tick, allowing publication to finish first. Recoverable creation cancellation preserves source records even with a custom store.

Reported load or save failures leave the previous binding and live NPC instances active. Failed provisional instances release their entities, traits and deferred work. Cleanup failures after publication propagate to the caller with the replacement active; already released NPCs are not presented as a rollback. Store callbacks cannot recursively save, reload or replace the default store. Operations run on the server thread once a server exists.

Editors and chat prompts close after candidate loading succeeds, before the previous registry is saved. Their end callbacks and external backend/provider side effects are not transactional. Stores must report persistence failures through exceptions. Synchronous YAML write failures now propagate instead of merely printing an error. This change does not make filesystem writes atomic or undo writes already performed by a backend.

## Runtime ownership

Both old registry indexes are cleared before retirement callbacks. Retired registries reject creation and saving, and retired NPCs cannot respawn. Trait listeners, navigation, entity controllers, helper ownership and temporary lifetimes are released; despawn cancellation cannot retain an entity belonging to the retired registry. Retirement does not emit a deletion event or clear the store. Explicit removal of a current NPC still deletes its record normally.

Named, anonymous and temporary registries retain their own lifetimes. Default NPC traits release their own helpers. Respawn queues compare NPC object identity rather than UUID equality, preserving unrelated and provisional NPCs with the same UUID. Tick iteration snapshots registries and NPCs, then checks current ownership before updating each instance, so a switch from an NPC callback does not advance retired siblings.

Persisted click redirects retain their target UUID and resolve through their owning registry when used. A helper loaded before its parent can resolve the parent after loading completes, and a replacement cannot retain the old default registry's target. Missing target UUIDs survive saving and resolve if the target returns. Direct constructor references retain their explicit target behavior; player-filter child links follow resolved target changes.

## Validation

The first native fixture failed at `replacement_has_own_registry`. A subsequent targeted case reproduced `loaded_redirect_resolves_replacement_parent`. A filesystem test checks that a real obstructed YAML destination propagates through `SimpleNPCDataStore.saveToDiskImmediate` while retaining its memory snapshot.

Final native results:

| NeoForge | Runtime switch | Early startup | Late startup |
|---|---:|---:|---:|
| 21.1.248 | 78 | 81 | 81 |
| 21.1.233 | 78 | 81 | 81 |

All 480 checks pass. The existing provider absence/presence/absence regression passes 193 checks; creation passes 305, removal 62, selection 126 and packet interactions 78. General validation passes 107 NPC assertions and 223 probes. All 262 ordinary tests and 100 Interactions tests pass without failures, errors or skips. The default-version clean test/build succeeds.

All 17 production, fixture and reference checkpoints match the tested files. Release/source archives contain the implementation and exclude audit fixtures. Citizens contains no Interactions implementation, resources or embedded Interactions JAR; Interactions contains no Citizens classes and has unchanged entry names and contents against the preceding release. The original Citizens save is unchanged. Detailed source/report/log/archive hashes are consolidated in `artifacts/store-switch-validation-summary.json` by `artifacts/summarize-store-switch.ps1`. Reproduction instructions are in [the native fixture](../tools/store-switch-runtime-audit/README.md).

## Scope

This is Citizens persistence and reference-lifecycle work. Interactions conversations, conditions, actions, cutscenes and progress remain in its independent source set and NeoForge JAR. Dependency remains Interactions → Citizens. No original server data or installed modpack is changed.

Ordinary registry reload/shutdown and named-registry replacement retain broader lifecycle acceptance work, including arbitrary provider/trait failures. Strict trait serialization, atomic filesystem writes, configuration/shop reload transactions, external service/data contracts and physical-client/modpack/proxy acceptance remain on [the current checklist](remaining-citizens-work-2026-09-26.md). The full Citizens goal remains open.
