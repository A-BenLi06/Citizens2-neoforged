# Native removal and undo parity

Validation date: 2026-09-13, UTC+08:00.

## Restored command behavior

`/npc remove` and `/npc rem` now resolve the specified ID, UUID or name instead of
deleting an unrelated selected NPC. Omitting a target still removes the selected
NPC. The owner, entity UUID and world filters are restored, with the upstream
filter precedence and ownership checks. Single-target removal requires its normal
permission; remove-all accepts the upstream `citizens.admin.remove.all` or
`citizens.admin` permission, as well as the previous port's
`citizens.npc.remove.all` alias.

Owner filtering supports player UUIDs, online/cached player names and explicit
server ownership. World filtering accepts actual dimension IDs, recognized world
folder names and Bukkit UUIDs read from existing dimension-specific `uid.dat`
files. A misspelled or ambiguous command world never uses the permissive
saved-location overworld fallback. The existing saved-data resolver is unchanged.

Removal records a per-sender undo entry and forwards the command source through
the existing command-removal event. Removing another NPC preserves the sender's
unrelated selection. Remove-all stays scoped to the default registry, as upstream.

## Recoverable snapshots

The new `NPC.saveSnapshot` path includes temporary NPCs without changing their
`SHOULD_SAVE` flag or writing them to persistent storage. The ordinary `save`
policy remains intact. Snapshot serialization must succeed before a removal can
rely on it; it also preserves pending trait-removal markers for the next ordinary
save. NPC copying uses the same complete snapshot path.

Undo retains the source registry, ID, UUID, traits and navigation settings.
`CitizensNPC.load` already handles deferred respawn, so undo uses that lifecycle
rather than spawning an extra entity itself. Occupied IDs/UUIDs are rejected before
creation, and the failed history entry stays available for retry. Removed named
registries are reported instead of restoring into a different registry.

## Deferred name selection and command outcomes

An ambiguous player command opens the existing chat selector. `exit` cancels it.
Answers resolve the originally offered UUID in its own registry, so reusing a
numeric ID while the prompt is open cannot redirect deletion to another NPC.
Ownership and permissions are checked again when the answer executes. Console
ambiguity remains an error naming the candidates.

The public `CommandManager.executeSafe` API reports whether a command was handled,
including handled failures. Brigadier now uses the checked execution path and
receives command failures as `CommandSyntaxException`. A rejected Citizens command
therefore produces a failure callback and stops later dialogue actions. Returning
the number zero alone would not have supplied that failure signal.

## Validation

- 44 checks pass in the dedicated removal-command fixture:
  `artifacts/repair-removal-runtime248-wired.log`.
- The existing isolated suite passes 107 NPC assertions and 223 runtime probes:
  `artifacts/repair-removal-regression248.log`.
- The ordinary build and 124 tests pass:
  `artifacts/repair-removal-final248.log`.
- The reproduction procedure is in `tools/removal-runtime-audit/README.md`.

The first attempt did not execute the probe because its subscriber named the
Citizens mod while the class belonged to the Interactions source set. Its live
process was confirmed healthy with a thread dump and then stopped by its verified
workspace/port identity; it is excluded from acceptance. The subscriber was
corrected and an outer three-minute task timeout added. Evidence is retained in
`artifacts/repair-removal-runtime248.log` and
`artifacts/removal-initial-thread-dump.txt`.

The tests use actual registered commands, the chat event listener/scheduler and
embedded player connections in a separate local world. They do not delete
production NPCs or establish real client input behavior.

The existing permission API is exercised with scoped temporary grants and ordinary
operator defaults. Source inspection found that its node-registration helper is
not yet connected to NeoForge permission gathering. Full external permission
provider integration therefore remains a separate open goal item, alongside the
other feature gaps in the consolidated parity report. No claim of complete
Citizens/companion parity is made by this repair.
