# NeoProtect / GriefLogger attribution and rollback

2026-10-19, UTC+08:00. ADAPT-09 follow-up for the selected NeoProtect 1.0.4,
GriefLogger 1.2.10-1.21.1-yuuniverse.1 and Economy 0.5.6 providers.

## Placement ownership

NeoForge dispatches `EntityMultiPlaceEvent` to both its own listeners and base
`EntityPlaceEvent` listeners. NeoProtect 1.0.4 records the base snapshot in
`onBlockPlace` and every snapshot in `onMultiPlace`, duplicating one placement.
The retained unmodified-provider audit reproduces the incorrect door count.

Citizens' optional mixin wraps only the base callback's `enqueueBlockChange`
call. Multi-place events are recorded by the dedicated listener; ordinary
placements still invoke the original write. The shared NeoForge event, other
subscribers and NeoProtect's subsequent fire cause tracking continue normally.
There is no time-window deduplication or guessed actor. The method descriptor
and invocation are pinned to the inspected provider. An incompatible present
provider must be validated before upgrade; the required invocation does not
silently stop matching. Absent NeoProtect is allowed through `@Pseudo`.

A native door use places two halves with one record each. Rollback removes one
half; Minecraft's neighbor update removes the other, which NeoProtect reports
as skipped because it already matches the restored state.

## Log owners and financial boundaries

Both loggers retain their own native observations. One record in each database
is expected; the databases are not merged. Native vanilla/Create placement and
break actions retain actual player UUIDs and registry identity. GriefLogger's
entity-death observation retains the killer; Citizens protection preserves its
owned entity under the same native attack. NeoProtect exposes block/container
history, not a general entity combat history in this version.

Native container removal retains seven items and their custom-name component.
NeoProtect preview leaves the container unchanged, and rollback restores the
exact quantity/components. World rollback does not remove the previously taken
items from the player's inventory. Administrators must reconcile ownership and
inventories explicitly when choosing container restoration.

Economy's programmatic stock mutation is not a manual player container click
and produces no fabricated `CONTAINER_TAKE` observation. Its public commerce
history owns the request key, operation UUID, transaction UUID, shop ID, actual
trade count and financial settlement. Join those identities for economic
analysis; timestamps and nearby block logs do not prove a financial reversal.
Current shop lookup supplies the physical position separately; the receipt does
not promise an immutable historical coordinate if shop definitions change.

Native rollback may remove the shop block. It preserves the committed balance,
receipt and delivered items, rejects a new quote for the unavailable container,
and replays already accepted terms without another charge or delivery. Clean
restart retains the same actor, one original transaction, world state, delivery
and both independent histories. Do not reverse money merely because a world
rollback succeeded.

## Validation and remaining acceptance

The [native audit](../tools/logging-runtime-audit/README.md) passes 37 checks on
21.1.248, including a burst of 96 actual placements and 96 breaks retained once
by each logger. The burst timing is fixture evidence, not a production service
rate. Eight clean-restart checks also cover restored container components and
both complete actor-specific burst histories. Exact provider, log and release
hashes are recorded in `docs/logging-attribution-validation-2026-10-19.json`.

Neither inspected provider exposes a supported timed retention/purge policy;
NeoProtect's queue capacity, flush/batch settings and lookup limits are separate
from history expiry. GriefLogger's public lookup has a 1000-row bound. Preserve
each stopped database separately before any future retention migration. This
batch does not delete or age records and does not claim indefinite operational
capacity or full ADAPT-09 completion.

The [2026-10-22 process-kill follow-up](logging-crash-recovery-2026-10-22.md)
now verifies a forced JVM termination after observed flush and explicit saved
player/world state, with all 37 native and eight restart checks passing. It also
records read-only integrity checks for the two SQLite databases.

The [native fire follow-up](native-fire-logging-2026-10-22.md) repairs vanilla
burn/spread observations and verifies bounded native fire and owned/unowned TNT
attribution, configured tracking, optional absence and restart.
The separate [lava follow-up](native-lava-logging-2026-10-22.md) adds both native
lava ignition branches and preserves actual fluid-event state selection.

Queue overflow, database failures, termination during writes, retention tooling,
wider modded inventories/entities and environmental cause paths, production load,
and integrated physical clients remain open. The combined provider fixture needs
Economy's 21.1.247 floor; the Citizens 21.1.233 baseline is validated separately.

The [2026-10-23 queued-write follow-up](grieflogger-write-recovery-2026-10-23.md)
repairs an actual transient SQLite write loss in GriefLogger `.1`. Provider `.2`
retains immutable ordinary/batch parameters until commit, retries complete known
rollbacks and stops automatic replay for uncertain outcomes. Acceptance passes
27 actual-bytecode SQLite, 37 native and eight restart checks. Unflushed JVM death,
full-device failures, uncertain-outcome administration, retention/capacity/load,
MySQL and integrated release acceptance remain open; production stays on its
previous provider set.

The [native SQLite outage follow-up](native-logging-storage-2026-10-23.md) extends
that repair with real in-server locked writes, worker retries, later native
producers, independent H2 logging and exact storage/history restart: 33 checks.
A fresh normal regression passes another 45 checks. Launch guards now run even
when preparation is skipped; four negative invocations preserve all native files.
Unflushed termination and broader failure/retention/load/release gates stay open.

The [reader-blocked COMMIT repair](logging-reader-recovery-2026-10-23.md) builds
provider `.3` after reproducing permanent queue suspension in `.2` outside and
inside Minecraft. Known DEFERRED SQLite BUSY plus successful rollback may retry;
other uncertain outcomes stay fenced. Acceptance passes 53 standalone and 111
native/restart assertions across reader, writer and normal logging/commerce runs.
Pending work remains in memory and the broader gates above remain open.

The [SQLite FULL repair](logging-full-recovery-2026-10-23.md) builds provider `.4`
after reproducing permanent queue suspension when SQLite itself aborts a write
transaction. Recovery requires pre-commit typed FULL, native rollback proof, no
commit hook and successful DEFERRED transaction restart. It preserves uncertain
outcome and legacy-batch fences. Acceptance passes 93 standalone and 149 native/
restart checks with direct page caps and reader/writer/commerce regressions.
Physical device/IOERR/COMMIT-FULL, unflushed termination, administration,
retention/load, MySQL, migration and deferred clients remain separate gates.
