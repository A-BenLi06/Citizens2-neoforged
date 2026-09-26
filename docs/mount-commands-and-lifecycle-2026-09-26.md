# Mount commands and native riding lifecycle

## Command behavior

`/npc mount` now accepts `--onnpc <id|uuid>`, `-d` and `-c`. Branch precedence follows the original: dismount the issuing player first, otherwise resolve the requested NPC mount, otherwise cancel the selected NPC's mount request, otherwise mount the player through an existing enabled Controllable trait. Console sources can configure/cancel NPC mounts; a console `-d` is a no-op. Player-only mounting requires an actual player source. Selection, ownership and command permission checks remain in the normal command pipeline. Unknown options, extra arguments and invalid targets are rejected before mutation.

Command targets resolve in the default registry, matching the original. Persisted/API UUID requests resolve globally, including named registries. A target must be spawned when selected by command, but an unspawned rider may retain or cancel its pending request. Ordinary player mounting neither attaches nor enables Controllable implicitly. Canceling a native NPC mount works even if MountTrait was not previously attached.

## Requested state and actual state

The persisted `mountedon` UUID represents the desired mount. `getMountedOn()` derives the actual NPC vehicle from the native relationship instead of treating a canceled `startRiding` call as success. Unresolved targets, different dimensions and cyclic/self relationships cannot become successful mounts; valid pending requests can retry when circumstances change. Despawn preserves the request and suspends operations; removal retires the trait. Actual target entities are resolved again after respawn.

An unchanged old vehicle cannot overwrite a pending switch or restore a request cleared during a canceled dismount. A genuinely new external vehicle is adopted and persisted, including when a native listener chooses it directly during a Citizens operation. Reentrant ticks do not recursively replay the same request. Newer trait requests, entity/trait retirement and native vehicle changes supersede the old operation.

## Native event boundaries

`NPCMounts` runs native mount/dismount operations in a nested, exception-safe scope. Switching preserves the native order: the mount event runs before the dismount event, so rejecting a new mount keeps the old vehicle. The native internal detachment has its own scope. Before native code writes the new pose/vehicle, the Entity mixin checks that detachment actually succeeded: NeoForge's native `startRiding` otherwise continues after a canceled dismount and can leave the old passenger list inconsistent. The mixin also wraps the complete native event dispatch and revalidates ownership, entity identity, dimension, cycles and the expected vehicle. Event cancellation remains authoritative.

LivingEntity's post-dismount placement can call another dismount. Its scoped wrapper skips that placement when an event listener has selected a newer vehicle. ServerPlayer's scoped wrapper preserves vehicle-effect packets when the old vehicle is still mounted after cancellation. Calls outside a Citizens request retain their native behavior. No native indexes or passenger fields are written reflectively, and the helper retains native events, relationships, packets and player positioning on successful operations.

Controllable commands and right-click mounting share the guarded operation. A Controllable trait removed, disabled or replaced by an event cannot finish an obsolete mount or receive a stale right-click callback.

## Validation

The isolated dedicated server passes 141 checks on NeoForge 21.1.248. The project default remains 21.1.233. Reproduction and the fixture's intentional exception are explained in [the audit README](../tools/mount-runtime-audit/README.md).

The retained negative run proves the original canceled-mount false-success defect. Subsequent failing runs exposed direct native callback adoption and LivingEntity post-dismount cleanup; both now have passing regression assertions. A later negative control proved that preemptively dismounting before the native mount event wrongly loses the old vehicle on rejection. The final implementation retains the native event order and passes that scenario. Two other iterations corrected the fixture's expectation of Brigadier errors and its use of the deprecated string owner setter; they are not production defects.

Final sequential regressions pass 170 packet-hologram/mount, 43 packet-viewer, 129 follow, 81 movement/list and 62 removal checks, plus 107 NPC assertions and 223 general runtime probes. All 232 ordinary tests pass with zero failures, errors or skips. Clean build succeeds. These regressions and the clean test/build were repeated after the final event-order correction; the earlier successful runs are retained separately.

Release/source jars include the implementation and all three configured mixins, and exclude audit/provider fixtures. Interactions archive entry contents match the retained reference despite ZIP timestamp differences. Original Citizens saves retain their recorded hash. Consolidated results, source/reference/fixture hashes and archive hashes are in `artifacts/mount-validation-summary.json`, generated by `artifacts/summarize-mount.ps1`; the root walkthrough records the final timestamp and decisions.

## Remaining work

This batch does not implement AbstractNPC's whole mount-tree dimension-transfer/retry behavior. That remains distinct from retrying a saved mount after both entities become available in the same world. Physical-client seats/interpolation, arbitrary modded entity contracts, combined provider/modpack acceptance and actual proxy arrival remain unverified. Production installations and original saves were not modified.
