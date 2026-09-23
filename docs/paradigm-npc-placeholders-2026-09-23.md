# Citizens values through Paradigm's placeholder API

This optional bridge follows the [retained NeoForge ecosystem guidance](neoforge-ecosystem-replacements-2026-09-22.md). Citizens owns the NPC values; Paradigm owns their use in its formatting surfaces. The installed Paradigm 2.4.2b jar supplies the actual public `PlaceholderService`, `ExternalPlaceholderResolver`, `PlaceholderContext` and `Registration` contracts. No provider implementation or Bukkit expansion runtime is bundled.

## Exported values

The four keys match the checked-in reference `CitizensPlaceholders` expansion. Paradigm uses brace syntax:

| Placeholder | Value |
|---|---|
| `{citizens_selected_npc_name}` | The sender's selected NPC full name |
| `{citizens_selected_npc_id}` | Its Citizens numeric ID |
| `{citizens_selected_npc_uuid}` | Its Citizens UUID |
| `{citizens_nearest_npc_id}` | The nearest NPC in the viewing player's native nearby-entity query |

Selection is per player. A server context, or a player UUID that is not online, uses the console selection, matching the reference. Missing selection produces no value, which Paradigm renders as empty. A despawned registered NPC can remain selected; destroying it invalidates the selection.

Nearest-NPC lookup reuses Citizens' existing 25-block native cuboid query in the player's current dimension. It is independent of selection and returns empty when no candidate exists. As in the reference, it queries world entities and does not use viewer visibility as an authorization filter. Virtual PacketNPC entities are absent from that query. These semantics do not claim that the result identifies a visible, clickable or player-selected entity.

## Registration, lifecycle and threading

The bridge installs after provider startup, checks the external-placeholder capability and uses reflection only against the public Paradigm API. It retains each registration handle and closes its own handles on Citizens shutdown. Repeated installation does not increment provider reference counts. If a key is already registered, the provider retains that resolver; Citizens closes the duplicate handle and rolls back its partial batch. Other registrations remain owned by their original registrants.

The server tick detects an unavailable or replaced public placeholder service. It releases old handles, clears snapshots while unavailable, and registers against a new service. A failed registration is retried when the service changes or the bridge is explicitly uninstalled and reinstalled; the bridge does not overwrite a competing resolver.

Normal Paradigm message delivery executes on the server thread, where values read current Citizens selection and entity state. For a formatter called on another thread, the bridge serves an immutable snapshot from the last completed server tick, including the console and current online players. This avoids reading live world state or blocking the server on a formatter thread. Such reads may lag by one tick. Shutdown clears the snapshot and retires the resolvers.

This batch exports the four owned values. It does not add inbound Paradigm evaluation to Citizens' `<placeholder>` parser or automatically migrate arbitrary `%PlaceholderAPI%` expressions. Provider-owned economy/quest values still require their selected native service contracts.

Read-only public-API inspection on 2026-09-23 confirms that installed Paradigm 2.4.2b exposes registration through `PlaceholderService` and delivery through `MessageService`, but no format/evaluate method returning resolved text. Calling message delivery or private formatter internals would not provide a supported inbound evaluation adapter. That boundary remains open pending an appropriate public provider contract. The installed jar remains SHA-256 `9547f26740b6c6158078191055863a2ad5f87f5ce34ecf4e837f715486709353`; inspected signatures are recorded in `artifacts/scoreboard-paradigm-public-api.txt`.

## Validation and limits

The [isolated fixture](../tools/paradigm-placeholder-runtime-audit/README.md) uses a copy of the installed provider jar, synthetic players admitted through the native PlayerList and actual `MessageService.sendPlayerMessage` delivery. It captures native system-chat packets independently of the resolver. The provider-absent fixture verifies optional loading and unchanged Citizens placeholders. Read-only reflection observes Citizens handle cleanup; the provider registry's actual formatter exercises worker-thread context resolution.

The expanded fixture replaces the real Paradigm API instance through its provider registry using the existing real services, then waits for normal Citizens ticks to reconnect. This verifies the API replacement boundary, not an entire mod reload. The fixture does not install anything into production or use production NPC/player/permission data.

Validation recorded: 2026-09-23 20:00:40 +08:00 (UTC+08:00). The expanded actual-provider fixture passes 72 checks and the provider-absent fixture passes 8. Existing actual-provider regression passes 101 permission and 33 permission-shop checks. General runtime regression passes 107 NPC assertions and 223 probes; 217 ordinary tests pass with zero failures/errors/skips. All Gradle processes ran sequentially and completed successfully. After adding only the API-replacement fixture cases, the provider audit passed again and the final normal build restored unchanged production outputs from cache; the ordinary tests were up-to-date. No new production behavior was edited after the broader regression.

Evidence: `artifacts/paradigm-placeholder-validation-summary.json`, generated by `artifacts/summarize-paradigm-placeholder.ps1`, includes source/reference/fixture/log/report/jar hashes. Release/source jars contain the bridge and exclude audit classes and the Paradigm provider/API classes. Citizens jar SHA-256: `60a56de9aa2fa8b23965e44a2479a216caf0e85d630c61842ed20cf6c0a8941a`; sources: `d377560735381c9bd9c47a844b2aa885b23877cf40305d05d03e09bbfe2dfbf0`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Both the installed and isolated Paradigm jars remain `9547f26740b6c6158078191055863a2ad5f87f5ce34ecf4e837f715486709353`; original Citizens `saves.yml` remains `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Physical-client/modpack acceptance, all Paradigm formatting surfaces and a complete provider reload remain outside this fixture's proof. Unrelated dedicated runtime suites retain their historical evidence.
