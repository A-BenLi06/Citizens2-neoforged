# Create depot and belt pressing evidence

Date: 2026-10-19, UTC+08:00. Implements another bounded part of CRAFT-03–05
and ADAPT-05. The full port remains active.

The selected Create 6.0.10-mc1.21.1-performance-v6 computes transported pressing
results inside MechanicalPressBlockEntity, then stores them separately in
BeltPressingCallbacks.whenItemHeld. The new optional wrapper keeps observation
open across that complete native call. Simulation, matching, bulk configuration,
RNG, callbacks, remainder production and transport placement remain Create-owned.
It never awards progress merely because tryProcessOnBelt returned computed results.

## Storage and confirmation

RecipeApplier retains every actual native roll and remainder before Create merges
them. The actual recipe object must be the unique current registered holder;
transient sequenced-assembly recipes cannot be relabeled as ordinary pressing.
An authenticated assignment to a live press and an online non-fake beneficiary
are still required. Durable PREPARED admission precedes native transport storage.

The adapter snapshots storage when an actual recipe invocation begins, immediately
before storage, immediately after storage and again on the native callback return.
It requires the exact input object and its original components/count, the exact
handler's live holder, a native changed result, and a successfully returned storage
operation. All unrelated stored items must remain accounted for. Confirmation
compares component-sensitive multisets of the consumed input, retained input,
actual rolled results, native remainders and previous inventory contents.

Depot evidence reads all nine native item-handler slots, including the held item
and output buffer. Native Containers.dropItemStack insertions are observed only
inside the admitted storage scope. Every accepted overflow entity must still exist
with its exact components/count. Rejected, missing or changed overflow requires
review without task credit; nearby entities never substitute for native output.

Belt evidence reads the pinned items/toInsert/toRemove lists through a mixin
implementing a read-only Citizens bridge. Its logical snapshot is current items
excluding queued removals plus queued insertions. It does not call BeltInventory.write,
which flushes queues and would change gameplay state. The controller's exact
inventory identity is checked. Confirmation proves native accepted pending storage;
the next-tick queue merge is separately exercised in the fixture.

An unchanged TransportedResult is intentionally skipped by Create and cannot
confirm evidence even when input/output multisets happen to match. Input removal
or count changes during admission, output changes, failed storage, or native
interruption leave the durable observation unconfirmed. Existing FTB admission,
team/repeat generations, REVIEW handling and absolute progress projection apply.
Only actual primary output units count; remainder and secondary outputs are retained
for verification. Existing observation constructors stay compatible and only world
pressing carries an input entity UUID. Transport payloads include the copied prior
inventory contents in beforeOutput.

## Native acceptance

The opt-in TransportPressRuntimeAudit creates a synthetic powered press, depot and
six-segment belt using native motor and belt-connector setup. It ticks native
machines rather than invoking processing methods to manufacture completed crafts.
Only the explicit simulation check calls the simulation API. Saturated buffer
setup uses the inspected provider field in the isolated fixture.

Both NeoForge 21.1.233 and 21.1.248 pass 21 checks:

- Powered native transport, unassigned rejection and simulation isolation.
- Bulk and single depot cycles with exact components, held input and buffer results.
- Duplicate result/outcome idempotency and reopened journal absolute totals.
- Full-buffer native overflow with exactly-once remainder callbacks.
- Canceled/changed overflow and removed/changed original input remain in review.
- Unchanged native result skipping and preserved pre-admission remainder exception.
- Powered connected-belt bulk/single cycles with native pending queues, next-tick
  output preservation and native remainder callback counts.

Each run also passes 54 existing Create checks, 23 batch checks and 16 world-press
checks. Final absent-provider, ordinary-build and archive results and exact hashes
are recorded in create-transport-press-validation-2026-10-19.json.

Retained development failures document two fixture corrections: inserting the next
belt input before the previous press cycle finished, and trying to replace an
immutable existing quest binding with an unrelated recipe. The fixture now waits
for native cycle completion and uses a separate task for unchanged-result evidence.
The immutable production binding contract was retained.

## Remaining acceptance gates

Basin mixing/compacting, fan processes, deploying, filling/emptying and sequenced
assembly remain required. Transport-specific actual process-kill recovery, all
inventory mutation/rollback points, wider party/repeat/offline/reconnect and
financial-backup reconciliation are still open. Accepted belt queues are not an
atomic transaction with saved world, FTB and journal files. No such durability or
arbitrary rollback guarantee is claimed.

The selected logging operational matrix, missing legacy content provenance,
supported Paradigm inbound formatting and physical VR/non-VR integrated migration
acceptance remain in the authoritative goals. Citizens and Interactions remain
separate JARs. This work changes development code/fixtures only and activates no
production mod, configuration, world, ledger or process.
