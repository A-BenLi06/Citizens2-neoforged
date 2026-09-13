# Native permission integration

Implementation follow-up for 2026-09-13 (UTC+08:00), after `d01d3ca`.

Later follow-up: [contextual group queries](group-services-parity-2026-09-13.md) resolves the world/dimension membership limitation recorded below and documents the remaining primary-group and permission-writing requirements.

Subsequent follow-up: [permission shop transactions](permission-shop-parity-2026-09-14.md) installs a permanent writer with reversible changes and checked shop outcomes. Temporary external attachments and primary-group replacement remain separate gaps.

## Changes

Previously `PermissionUtil.register()` was never called and no Citizens nodes were submitted to `PermissionGatherEvent.Nodes`. Its node constructor also produced names such as `citizens.citizens.npc.create` and replaced `*` with `_`. Ordinary players consequently fell back to operator checks despite permission-provider grants.

The mod now registers the upstream declarations, discovered command/flag permissions and relevant native registry/manual nodes before NeoForge initializes the handler. Permission identities retain their exact dotted names and wildcards. The original descriptor is packaged as `citizens/permissions.yml`; it is the source for defaults and declared child relationships, rather than a second hardcoded permission catalog.

The descriptor contains 615 definitions. Actual SnakeYAML parsing and Bukkit's `Permission.loadPermission` establish that its 595 compact entries such as `{default:false}` do not contain a `default` key; they inherit Bukkit's OP default. The other 20 entries explicitly default to OP. This change preserves those effective defaults instead of silently interpreting the malformed inline keys as a different policy. Registered permissions are metadata, not a claim that all associated upstream commands have been ported.

Registered nodes use NeoForge's selected handler. With `paradigm:internal` selected, runtime permission strings also use Paradigm's live tri-state query. This covers custom shop requirements, command permissions and `interactions.start.<conversation>` after startup. Its public online query computes world, dimension, server and network contexts; using the versioned UUID facade with `GLOBAL` would lose that information. The bridge is optional, checks provider availability/capabilities and denies access if the selected provider's runtime bridge fails. It does not replace another selected handler or bundle provider code.

OP/source elevation is supplied to a node's fallback resolver. An explicit backend denial is no longer overwritten by a subsequent `|| source.hasPermission(2)`. Citizens' existing separate `citizens.admin` command override is retained: deny both the command and the admin override when testing an execution denial for an operator. Local temporary attachments remain explicit overrides inside Citizens.

Further related repairs:

- `--id`/`--uuid` targeting checks the canonical `citizens.npc.select` permission instead of the unrelated `npc.select` name.
- Help for aliases such as `wp` and `tpl` checks the primary command's help permission, matching Bukkit alias dispatch. Non-operators do not need an additional alias-specific permission.
- Explicitly supplied restricted flags, including aliases, are checked before validators/command execution. An omitted flag can still use its ordinary default. The current upstream API also only filters flag completions; this is additional hardening rather than a newly restored upstream feature.
- Paradigm 2.4.2b's `resolvedGroups()` reports assignments without flattening parent groups. Citizens now follows the public group-info inheritance graph, with cycle protection, so guard/shop group queries recognize inherited groups. The resolver is released on server shutdown.
- Local temporary grants normalize names, copy mutable inputs, unwind a failed external grant, expand declared parent children and keep old attachment handles from revoking grants created after logout. Overlapping grants and repeated removal retain their intended lifetime.

## Verification

The real-provider run passed **58 checks**, including persisted permission lookup after a full restart, canonical help aliases and a completed storage queue before shutdown: `artifacts/repair-permissions-final-provider248.log`. Earlier in-memory and pre-alias runs are historical evidence, not the final acceptance run.

The isolated audit and preparation instructions are in [`tools/permission-runtime-audit/README.md`](../tools/permission-runtime-audit/README.md). Ordinary permission changes use real provider commands; wildcard/Unicode fixture rules use its public mutation API. The opt-in tests simulate connected server players, not a physical client UI session.

The final source also passes:

| Validation | Result | Evidence under `artifacts/` |
|---|---|---|
| No-provider fixture | 17 checks | `repair-permissions-fallback248.log` |
| Removal/undo regression | 44 checks | `repair-permissions-removal248.log` |
| Existing NPC scenarios and runtime probes | 107 + 223 | `repair-permissions-regression248.log` |
| Legacy dialogue unit cases | 40; no failures/errors/skips | `repair-permissions-dialogue248.log` |
| Ordinary tests and build | 131; no failures/errors/skips; build passes | `repair-permissions-final248.log` |

`artifacts/permission-validation-summary.json` records the results and final jar hashes. Normal jars contain no runtime probes or provider implementation classes; the Citizens jar includes the unchanged source permission descriptor. The Citizens jar SHA-256 is `17fa2b959f309c5357ec6c2ca69235fba15914eaa6c67f2b1076e026dda8904a`.

The provider startup also logged a `holograms.json` load error in the isolated fixture. These checks cover permissions, not acceptance of that provider module or the complete modpack.

## Remaining permission boundaries

- The `manuadd` alias is still the wrong Paradigm command path. Its 263 old actions also require GroupManager replacement/primary-group behavior, not simply another group membership. This batch does not implement rank mutation.
- No `PermissionWriter` or external `TemporaryPermissionGranter` is installed. Permission-selling shop actions remain unavailable, and local temporary grants do not become global grants visible to other mods' own checks.
- The provider's versioned UUID group metadata uses server/network contexts. It does not supply world/dimension-scoped group memberships to the current group-query bridge. Live contextual **permission checks** are covered; contextual **group membership queries** remain a separate gap.
- Paradigm 2.4.2b's own NeoForge command parser rejects `*` and Unicode in permission arguments. Reading API-created rules works; that CLI limitation has not been changed.
- The additional public online/group-info methods are outside Paradigm's small versioned facade. They are runtime-checked and tested against 2.4.2b, not a promise of compatibility with every future provider build. Other providers can install the generic resolver hooks; arbitrary dynamic permission names are not automatically supported by every NeoForge handler.

No production deployment or production permission-data migration was performed. CmdCam remains the camera provider and Yuuniverse Economy remains the owner of economic APIs.
