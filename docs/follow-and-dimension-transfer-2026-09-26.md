# Follow commands, navigation ownership and dimension transfer

Minecraft 1.21.1 / NeoForge 21.1.248. Scope follows the [native ecosystem guide](neoforge-ecosystem-replacements-2026-09-22.md). This batch recovers the interrupted work on `ae6dd02`; the earlier `send_to_server` implementation remains documented in [the transfer follow-up](dialogue-server-transfer-parity-2026-09-19.md).

## Follow command and target identity

`/npc follow` supports explicit `--enable true|false`, the existing `-c` cancellation alias, protection and a configurable margin. A margin-only command changes the margin without toggling following. Margin values must be finite and nonnegative, or `-1` for the inherited default. Boolean/flag validation and permission/ownership checks precede configuration changes. Repeated explicit disable is harmless. An omitted enable target means the player issuing the command, including when already following someone else; a console must supply a target. This follows the original sender-default contract.

An admitted player can follow themselves without `citizens.npc.follow.others`; another player or NPC requires that permission, and another NPC also requires ownership. Self-targeting NPCs are rejected. Ambiguous NPC names use the existing selection prompt. Each valid command supersedes earlier pending follow choices, including disabling an NPC which has no FollowTrait yet. Delayed choices recheck ownership, permission, registration, trait identity and configuration revision before applying.

The persisted fields remain `followingUUID`, `margin` and `protect`. Targets are resolved against current player sessions or live NPC ownership. Player NPCs can use a different native profile UUID from their Citizens UUID; virtual player targets resolve by that native identity even though they are absent from world indexes. Despawned targets and disconnected sessions stop the owned route while retaining the saved target UUID for later reacquisition.

## Navigation and protection

FollowTrait records the exact strategy it starts. Disabling, changing settings or removing the trait cancels that strategy only; an independently installed route to the same entity survives. Flocking runs only during the unpaused owned route. Margin configuration is installed before the native navigation-begin event, and callbacks cannot resurrect a disabled or removed follower.

Navigator completion/cancellation callbacks, event listeners and strategy factories can submit newer requests. The older operation now preserves that newer request. Strategy cleanup detaches the old route before calling its `stop` method, preventing recursive cancellation and erasure of a replacement. Despawn temporarily prevents new routes from being left attached to the retiring entity. Trait replacement unregisters the replaced instance's native event listener.

Protection consumes actual native incoming-damage events and targets the causing entity, including an arrow's shooter. Already-cancelled damage, the follower itself, an unavailable attacker and a retired target do not start retaliation. Disabling protection releases an owned attack route.

## Native dimension transfer

The interrupted version retained the removed entity in its controller after native cross-world movement. Its attempted virtual transfer also called protected `Entity.setLevel`; merely changing that field would leave world-bound components such as a mob's PathNavigation attached to the old level.

Non-player NPCs now use the native entity factory and `restoreFrom` copy/remove/add sequence. The controller and NPC mapping adopt the survivor before native destination admission, so join/tracking callbacks see the correct NPC and its visibility policy. Ordinary entities enter the destination through native entity admission; virtual entities receive a new tracker without entering either world's entity manager. Native state, UUID and rotation survive the tested transfers, while mob navigation is constructed for the destination level.

Native player NPCs retain their entity object. World players use native player teleportation and immediately reapply the configured world-player-list policy. Virtual players update their server/game-mode level without being admitted to world entity or player collections. Old virtual viewers are unpaired before transfer. A removal or replacement during unpairing prevents the retired operation from restoring the entity.

The public teleport event is dispatched before movement or route changes. Cancellation preserves the current entity and route; a newer callback teleport supersedes the older request. Destination chunks are loaded through the existing NPC teleport contract. Temporary navigation suspension is released even when a callback replaces the transfer with a same-world move.

## Validation

The dedicated fixture passes 129 checks. It uses real command dispatch, native permission handling, admitted synthetic players, native damage, persistence reload, server ticks and real world/virtual entity lifecycles. Ordinary and virtual cows and player NPCs cross dimensions and return. Checks cover health/UUID/rotation retention, ownership during destination admission, native world indexes, virtual exclusion, old viewer cleanup, correct PathNavigation level, cancellation and destructive callbacks. Read-only reflection observes the native navigation level; it does not modify native indexes or pairing membership.

The fixture requests its destination chunk through a normal forced-chunk ticket and waits for actual ticking readiness. An earlier same-tick assertion queried an inaccessible destination chunk before native entity indexing became visible; that failed run is retained. Another earlier fixture attempted damage before its invulnerability setting was applied at spawn, and one compile attempt referenced a nonexistent fixture-only metadata constant. Those fixture failures are not reported as production defects. Original failing follow/permission/callback/cross-world evidence and the interrupted compilation error remain in `artifacts/follow-*.log`.

Sequential regression passes 63 LookClose, 64 rotation, 81 movement/list, 43 packet-viewer, 170 packet-hologram/mount and 62 removal checks, plus 107 NPC assertions and 223 general runtime probes. These broader regressions precede the final sender-default command correction; the affected follow fixture and clean ordinary test/build were repeated after it. All 232 ordinary tests pass with no failures, errors or skips. Release and source archives exclude audit/provider fixtures, and all Interactions archive entry contents match the retained reference despite changed ZIP timestamps.

Reproduction is documented in [the fixture README](../tools/follow-runtime-audit/README.md). Final regression and archive evidence is consolidated in `artifacts/follow-validation-summary.json`, including source/reference/fixture/log/report/archive hashes. Citizens SHA-256: `db78716be310561b8c77fc90e2ca1a80b039d4b57dc9f988a04ce2bb0ad4c49f`; sources: `f8f67975e4e4e729e6260a8edb4e9754a17988f72cece64ed853d9f1cb797bc9`. Original `saves.yml` remains `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

## Remaining acceptance

This proves the exercised server paths, not physical-client rendering, signed skin-service behavior, combined modpack/proxy acceptance or every modded entity's transfer behavior. The NPC override transfers the selected entity using native dismount behavior; it does not yet reproduce AbstractNPC's entire mount-tree transfer and retry behavior. Those remain implementation gaps beyond the unmounted transfer cases covered here. Client interpolation, mount/seat presentation and the broader command/API audit remain open. No production deployment, provider replacement or original-save modification is part of this batch.
