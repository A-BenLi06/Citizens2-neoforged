# Group queries and GroupManager migration requirements

Updated 2026-09-13 22:48:05 +08:00 (UTC+08:00), after `d11bb9c`.

## Implemented contextual membership queries

Paradigm's versioned `metadata(UUID)` result omits world/dimension-scoped assignments. Its public `getPlayerPermissionInfo(UUID).groupAssignments()` retains the assignment context, expiry and group name. Citizens now combines the provider's ordinary/default/server memberships with matching live assignments, then follows the actual parent-group graph.

Context matching is delegated to Paradigm's own `PermissionContextResolver` and `PermissionContextSet.match`. The resolver reads the current storage identity and wrapped player; Citizens does not copy server/network identifiers or synthesize a global context. Expired/denied assignments and assignments to nonexistent group definitions are excluded. A lookup failure still returns unknown, not a fabricated membership result.

Real-provider checks cover world and dimension assignments, inherited parents, leaving/returning to a dimension, revocation and actual timed expiry. The fixture continues ticking during the expiry wait and drains the provider's storage queue before stopping. All **65 provider checks**, **17 absent-provider checks** and **131 ordinary tests** pass; the normal build succeeds and jars exclude runtime probes.

Evidence: `artifacts/repair-group-context-runtime248.log`, `artifacts/repair-group-context-fallback248.log`, `artifacts/repair-group-context-final248.log`. The full existing NPC/runtime regression against `d11bb9c` remains the earlier evidence for unchanged native behavior; this follow-up adds direct coverage of the changed group-query path.

## Why changing the manuadd command path is insufficient

The original installed `GroupManager.jar` was checked directly:

- `ManUAdd` takes a player and destination group, plus an optional world. It calls `User.setGroup`, replacing the independent primary-group field. That setter does not delete subgroups or individual permission records.
- `ManUDel` removes/reset the user's record in the selected GroupManager world. Its second optional argument is a world, not a group to subtract.
- The supplied conversations contain **263** `manuadd` actions. All target `%player%`, all omit the optional world, and they reference **13** group names. Legacy world/mirror/default-selection semantics therefore still matter; console commands must not silently inherit the actor's current dimension as their original world scope.
- The old `udays` user file has 81 records, of which 42 have individual permissions. The old `world` file has 57 records, of which 27 have individual permissions. Neither inspected file has subgroups. These are records per world, not a deduplicated player count.

The installed Paradigm 2.4.2b data model has group assignments and contextual assignments but no independent user primary-group field/setter. Metadata chooses a primary group from group weights. Production has weights 0, 10 and 20 and **10 subjects with multiple explicit groups**. Removing all memberships would destroy other roles; adding one membership would retain obsolete roles; changing global weights would affect unrelated players. None is a faithful implementation of `manuadd`.

No replacement of that model, production permission mutation, or guessed group mapping was performed. A location/link for a maintained Paradigm source repository has been requested while independent parity work continues.

## Provider contract needed for primary-group compatibility

The following is a proposed capability, not an existing callable API:

1. Read and independently set a user's primary group for an explicit permission context, while preserving secondary memberships, inherited grants, direct permissions and unrelated contexts.
2. Return a reviewable before/after result or receipt, reject nonexistent/global-only groups and support idempotent retries. A stale expected primary/group state should be detectable before mutation.
3. Expose the independent primary consistently to permission evaluation, metadata, placeholders and presentation, rather than masking it in Citizens alone.
4. Provide a corresponding scoped user-reset operation for `manudel`, with a defined default group and exact ownership of the user data being reset. It must not delete unrelated homes, economy records or other service data.
5. Preserve the old world/mirror mapping explicitly during migration. Group names that occur in more than one old world must not silently collapse when their definitions differ.

Acceptance needs replacement in both directions, secondary groups with higher/lower weights, direct grants/denials, inherited rights, contextual isolation, offline UUIDs, errors without partial loss, retries and restart persistence. The current public add/remove-membership methods cannot prove those requirements.

## Data still requiring reconciliation

Read-only comparison found 11 of the 13 target group names in the production permission database:

| Source name | Finding |
|---|---|
| `timothy_player_fenxun` | Referenced by three actions. Defined in the old `world/groups.yml`, but missing from current Paradigm. The old `udays` data contains a different `timothy_player_fengxun` spelling; they must not be conflated automatically. |
| `udays_player_kazetatsumi` | Referenced by one action. Absent from both inspected old group-definition files and current Paradigm. This is inherited missing source data, not a permission-node registration problem. |

## Permission-writing follow-up

`PermissionWriter` and external temporary grants remain uninstalled. Before connecting a permanent writer, `PermissionAction` also needs correction: its current grant/take loops ignore mutation return values, and its unconditional inverse rollback can remove a pre-existing grant or restore the wrong state. Original native shop actions use a null/global world scope. A real implementation must preserve pre-existing, contextual, denied and temporary records, compensate partial writes, retain observable failures and avoid treating an inherited permission as a free consumable token. Temporary grants visible to other mods additionally need provider-owned attachment semantics, not a permanent-record workaround.
