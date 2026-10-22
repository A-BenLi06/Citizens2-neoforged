# FTB task query and NPC delivery adapter

Target: FTB Quests 2101.1.36, FTB Teams 2101.1.11, FTB Library 2101.1.37,
Architectury 13.0.11, Minecraft 1.21.1 / NeoForge 21.1.248. These exact local
provider jars and public signatures were inspected; no private FTB fields are used.

Citizens exposes `FtbQuestTasks.query(player, taskHexId)` as an immutable snapshot
of current FTB team identity, progress, maximum, started/completed and availability.
Each call resolves the current server quest file and player team. Queries do not
create teams or retain retired provider/task objects. Access requires the active
online player session and server thread. IDs are explicit FTB hexadecimal task
IDs; legacy Bukkit IDs are not automatically translated.

`/citizens quest status <task ID>` queries that snapshot.
`/citizens quest submit <task ID>` asks FTB to submit matching delivery items.
Both can be called through ordinary/packet NPC command traits with a player actor.
Console substitution does not create an actor. Messages use the existing editable
Citizens translation bundle.

Separate Interactions adds `ftb_submit: <task ID>` with read-only preflight and
current-authority execution. Conditions accept `%ftb_task_progress_<ID>%`,
`%ftb_task_maximum_<ID>%`, `%ftb_task_completed_<ID>%`, `%ftb_task_started_<ID>%`,
`%ftb_task_available_<ID>%` and `%ftb_task_team_<ID>%`. Boolean values are `true`
or `false`; unknown provider/task/field values remain unresolved, including in
negative comparisons. A successful command dispatch does not itself prove task
completion: inspect the authoritative progress/completed condition.

Delivery supports consuming FTB ItemTask instances that allow submission outside
the task screen. FTB owns matching components, count, inventory consumption,
progress, dependencies and team behavior. The adapter also preflights current
quest visibility/availability, the public sequential-task order, and available
matching inventory. Custom, possession-only, crafting-only and task-screen-only
requirements cannot be forced through delivery. No caller supplies a fabricated
craft stack or invokes arbitrary `setProgress`/`markTaskCompleted`.

FTB permits partial delivery; each submission uses items currently in inventory.
Repeating a completed task consumes nothing. An in-flight actor admission guard
blocks callback recursion across tasks sharing that inventory and always releases
on return/failure. Party changes resolve the new team on the next call; already
admitted work belongs to the team admitted by FTB. Repeatable-quest generations,
monetary rewards and receipt/craft evidence require their own supported adapters.

Follow-up 2026-10-22, UTC+08:00: [delivery outcome recovery](ftb-delivery-recovery-2026-10-22.md)
retains component-sensitive intent/outcome revisions for partial submissions,
checks independent player/task checkpoints and fences uncertain retries for
authenticated operator review. It never automatically replays consumption or
repairs native inventory/progress/rewards. Minecraft inventory and FTB persistence
remain independent; legacy content and broader ADAPT-04/05/release gates remain open.

Validation: 17 native FTB checks on 21.1.248, 121 actual Economy 0.5.5/CMDCam/
CreativeCore service checks, and the ordinary test/build pass. Separate binary/
source jars contain no runtime probes. Exact test counts/hashes and logs are in
`artifacts/ftb-task-validation-2026-10-18.json`.
