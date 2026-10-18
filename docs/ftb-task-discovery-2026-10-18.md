# Native FTB task discovery

`FtbQuestTasks.discover(player, afterTaskId, limit)` and `describe(player, taskId)`
query the current public FTB quest file and the actor's current team. They require
an online player session on the server thread. No quest file, team, progress or
reward state is cached or mirrored. Discovery reads the live object collection,
filters valid tasks and sorts native hexadecimal IDs unsigned. Pages contain
1–100 entries; a removed cursor remains usable and the returned next cursor is
absent on the final page. Returned records and lists are immutable.

Views expose native task, quest, chapter and task-type IDs, localized title JSON,
progress, availability, visible dependencies, sequential predecessor, repeatable
completion count and native start/completion timestamps. Reward views expose native
claim status, optional claim timestamp and player/team ownership. These cycle
observations are not durable monetary claim-generation tokens.

Task, quest and chapter visibility and hidden-until-startable details are honored.
Hidden prerequisites expose only a count, without their IDs or titles. Native item
tasks distinguish delivery, possession and crafting requirements; custom and other
task types remain explicit. Purchase/sale semantics are not guessed from titles.

Commands are `/citizens quest list [after ID]` and
`/citizens quest details <ID>`. Existing status and submission commands remain.
Interactions adds `ftb_tasks: [after ID]` and `ftb_task: <ID>`; preflight verifies
provider/session/visibility before any dialogue payment, and execution reacquires
current state. Details show prerequisites, repeatability and native reward status.
Listing and details do not consume inventory, claim rewards or advance tasks.

This completes a discovery subset of CRAFT-01. Durable generation/beneficiary
history, money rewards, commerce receipt application, attributed crafting evidence
and machine adapters remain separate requirements. Native FTB item crafting tasks
alone do not establish recipe-specific completion.

Validation covers actual pinned FTB Quests/Library/Teams, Paradigm, RSItemEditor
and KubeJS on NeoForge 21.1.248, including separate-party progress, hidden content,
sequential dependencies, stable pagination, native reward ownership and read-only
preflight. Exact final counts and hashes are recorded in
`artifacts/ftb-task-discovery-validation-2026-10-18.json`.
