# Native NPC creation options and lifecycle

Creation now supports the original unspawned, packet and center shortcuts, timed temporary NPCs, item-provider input, command-specific cancellation and creation undo. Validation precedes registry/NPC allocation. Explicit destinations, registry names, traits and templates require the NPC admin permission; entity-type permission and creation limits remain independent.

## Options and data

`-u` keeps the candidate unspawned and retains its requested location with a false spawned flag. `-p` selects packet transport before spawning. `-c` uses the native collision shape and the original partial-block height adjustment, with block-location orientation. Baby, silent and nameplate options retain their existing semantics. Invalid nameplate/location/item/duration input fails before mutation.

`--temporaryduration` implies the temporary registry, even when a registry name is also supplied. No unused named registry is created. Zero expires on the next native scheduler tick; durations use long tick delays without int overflow. Expiration belongs to the exact NPC and both registry indexes. Removal, undo, deregistration and shutdown cancel obsolete work. Plain `-t` remains session-only without timed expiry.

`--item` uses native item/component syntax. Unsupported combinations fail before allocation, and the registry snapshots the supplied stack. Block-state components apply to block displays, falling blocks and minecart displays. Supported non-minecart item carriers use the existing default provider when no item is specified; minecarts retain their native default display unless configured.

Falling-block NPCs use the original explicit-motion behavior without vanilla placement or self-dropping. Ominous-spawner NPCs remain visuals instead of emitting vanilla rewards. These hooks apply only to Citizens NPCs; ordinary controls retain native gravity/removal.

## Creation and cancellation

The native candidate has a preparation phase, so template data cannot transiently spawn it before acceptance. Stored template spawn state cannot defeat `-u`. Name caches initialize without a virtual constructor call; name-dependent traits initialize after construction, registration and type assignment.

Generic `NPCCreateEvent` cancellation releases the candidate and reports `NPCCreationException`. Command creation posts `CommandSenderCreateNPCEvent` or its player-specific subclass with the actual source and optional cancellation reason. Basic options are available then; optional traits/templates follow before admission. Cleanup removes only originating instances, preserving callback replacements with the same ID/UUID. Destruction is idempotent and finishes cleanup even when listeners throw.

Accepted creation enters undo history, including named and temporary registries. Failure discards only its own entry. Undo removes the entry it started with, preserving newer entries added by callbacks. Creation feedback uses translation keys and the normal English fallback.

## Validation

The isolated [creation fixture](../tools/creation-runtime-audit/README.md) exercises native dispatch, lifecycle, item metadata, templates and real scheduler ticks. It exposed UUID equality removing a replacement and constructor callbacks re-registering an already destroyed candidate. The failed fixture's `RemovedDuringInit` record was identified, archived and cleared only after verifying the fixture path and identity.

Final version counts, regressions, parser tests, source checkpoints and archive hashes are recorded in `artifacts/creation-validation-summary.json` and the root walkthrough. Intentional throwing-listener diagnostics are separate from unexpected failures. Interactions remains its own mod/JAR with unchanged entry contents.

Final dedicated runs pass 305 checks on both NeoForge 21.1.233 and 21.1.248. Regression passes 302 relocation, 1,639 death, 62 removal, 822 hologram-transfer, 101 item-hologram and 49 command-location checks, plus 107 NPC assertions and 223 general probes. All 100 Interactions unit tests and 232 ordinary tests pass. The clean default-version build succeeds; binary/source archives include the new implementation and exclude audit code. The original Citizens save hash is unchanged. No production deployment or physical-client acceptance is claimed.

The original create usage mentions `--model`, but its checked-in body does not implement it. Model-provider integration is not invented here. Remaining command groups, data/integration requirements and physical-client/modpack/proxy acceptance stay on [the current checklist](remaining-citizens-work-2026-09-26.md).
