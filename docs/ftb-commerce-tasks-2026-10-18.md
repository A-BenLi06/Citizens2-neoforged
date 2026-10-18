# Native commerce receipt tasks

Citizens owns explicitly bound FTB CustomTasks and projects committed Economy
receipts into absolute native progress. Economy 0.5.6 exposes Commerce capability
3: a stable ledger journal UUID, ordered receipt history, operation lookup and a
native observation before each new trade mutates inventory or money. Production
Citizens has no compile dependency on Economy or FTB. Interactions stays separate.

## Binding

An active operator with `citizens.admin` can use:

```text
/citizens commercetask bind <task ID> <system|coordinate> <shop ID> <offer ID|-> <buy|sell> <items|trades|minor_units> <currency ID|-> <settlement|delivered>
/citizens commercetask list
/citizens commercetask review
/citizens commercetask reconcile
/citizens commercetask resolve <entry ID> <team UUID> <cycle> <accept|reject> <reason>
```

Task IDs are native FTB hexadecimal IDs. System bindings require an exact enabled
offer ID; coordinate bindings use an exact shop ID and `-` for the offer. `items`
counts actual receipt quantity, `trades` counts recorded request quantity, and
`minor_units` counts the settled total in one exact configured currency ID. Other
measures use `-` for currency. Buy and sell have independent bindings. Settlement
permits pending delivery; delivered requires the provider's COMMITTED inventory
outcome. RECOVERY_REQUIRED never advances either policy.

Bindings start after the current provider sequence; earlier receipts do not grant
automatic historical credit. Definitions, provider UUID, maximum and repeat policy
are immutable. Bind only zero-progress CustomTasks, and disable independent button
or script progress writers. Craft and commerce ownership are mutually exclusive.
Quest/task/reward serialized definitions and order are hashed to fence reloads.
The caller-declared `origin` is retained metadata, not proof of an NPC interaction.

## Attribution and recovery

The starting observation forces PREPARED with the actual player's native team and
quest cycle. Receipt history and operation lookup establish settlement independently
of notifications. CONFIRMED is forced before projection; APPLIED follows the native
absolute progress check. A missing commit notification is recovered periodically
(one online actor each 20 ticks, up to 100 history rows per binding) or by reconcile.
Teams at trade time remain fixed after party changes. Duplicate notifications and
requests retain the original operation and cannot enter another repeat cycle.

Attributed receipts with pending delivery/recovery remain WAITING. When the public
provider outcome settles, replay confirms that same receipt and attribution.
Unattributed history, unknown bound trade quantity or currency mismatch remains
REVIEW. Operator resolution requires a live operator UUID and explanation, current
native definition/cycle and the exact immutable provider receipt/outcome. Original
attribution cannot be redirected. Absent legacy quantity is never inferred from
item quantity; a missing request key is retained using the committed operation's
identity for review. Failed uncommitted PREPARED requests never count as progress.

Files under `<world>/citizens/commerce-tasks` are forced immutable revisions guarded
by a single-writer lock. Strict schema, complete revision chains, operation/sequence
uniqueness and checkpoint evidence manifests detect inconsistent retained history.
Cursor checkpoints follow durable retention of all matching rows, including REVIEW.
Provider UUID changes and observed global high-water regression stop replay.

FTB saves independently. A process kill can leave retained APPLIED while FTB still
has an older progress total. Reconciliation rechecks the original public settlements
and restores the exact absolute total; it does not execute another trade. Lower
native repeat counters than retained attribution are fenced. A retained CONFIRMED
from an older cycle cannot be projected into a newer cycle. Save/restore the complete
commerce journal and Economy ledger with consistent task content. Deleting both
evidence and checkpoints or rolling every ledger copy back cannot be detected from
this consumer; arbitrary financial-backup rollback is outside accepted recovery.

## Validation and limits

The actual selected FTB/XMod/Rhino/Economy fixture uses NeoForge 21.1.248. The selected
JEI/Economy floors are preserved; ordinary Citizens tests/build retain 21.1.233.
Native checks cover system buy/sell, exact components, independent measures, finite
coordinate stock/money, craft separation, duplicate/repeat/reward behavior, full
inventory and post-checkpoint delivery, suppressed starting/commit events, party
changes, permissions, retired sessions, worker threads and definition changes.

The opt-in `tools/run-commerce-crash-audit.ps1` accepts `receipt`, `projection` and
`applied` stages. It kills only the PID from that fixture's durable marker after
verifying a Java server-run command line, then restarts the same isolated world.
Each recovery checks original operation/transaction, one ledger settlement, absolute
task recovery and one pending item delivery. No production restart/deployment occurs.

Exact final results and hashes are in `artifacts/ftb-commerce-validation-2026-10-18.json`.
Remaining acceptance includes broader multi-member reconnect/offline projection,
native repeat reset across inconsistent backups, inventory mutation kill matrices,
additional Create machines/assembly and the other integrated migration gates.
The overall porting goal remains active.
