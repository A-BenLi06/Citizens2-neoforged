# NPC parity status and changes since the initial audit

Initial audit timestamp: 2026-09-13 01:47:09 +08:00 (UTC+08:00).

Current summary updated: 2026-09-13 21:48:40 +08:00 (UTC+08:00).

Latest follow-up: [native permission integration](npc-permission-parity-2026-09-13.md) restores NeoForge node registration and exact names, connects Paradigm runtime permission checks with live contexts, repairs inherited group queries, canonical selection permissions and help aliases, and hardens flag execution/local attachment lifetime. The real-provider/restart run passes 58 checks and the absent-provider run passes 17. Group mutation, permission writing, external temporary grants and dimension-scoped group membership queries remain open.

Further native-command follow-up: [removal and undo parity](npc-removal-parity-2026-09-13.md) restores target/filter resolution, ownership, history, temporary snapshots and true dispatcher failures. Its dedicated checks and the existing regression suite pass.

Later implementation follow-up: [CmdCam and Yuuniverse Economy bridges](native-service-bridges-2026-09-13.md). That work connects the identified camera provider, adds native shop APIs, and corrects a further economic-format gap: 2,276 of 2,277 old eco actions select a currency and many recipients are offline. The detailed historical sections after the current summary retain the earlier snapshot; the linked follow-ups and walkthrough contain subsequent results.

## Current delta from the initial implementation

This section includes the follow-ups after the detailed historical snapshot below. Starting work is still measured from `f8ede13`; NPC creation, migrated NPC identities, skins, basic navigation, the initial traits and the initial dialogue engine already existed then.

| Area | What has now been added or repaired |
|---|---|
| Dialogue behavior | Persistent conversation cooldowns, remembered-dialogue conditions, randomized eligible lines, conditional/terminal routing, manual progression, tenth choices and MOVE/SCROLL/sneak selection; configurable presentation and JSON components. |
| Dialogue safety and entry | Live permission/air/radius/click gates, movement and input restrictions, requirement rechecks, preflight and observable action failures, registry-based sounds and vanilla command-root resolution. |
| Economic integration | Native Citizens payments/refunds and Yuuniverse Economy-owned dialogue shop APIs; explicit currencies, precision, and known offline account resolution. This is not a claim that every original economic product/service has been recreated. |
| Camera integration | CmdCam is the selected provider. All four referenced scenes were located in the original overworld/End data and exercised through the real-provider fixture. |
| Native NPC behavior | Persistent sneaking/default traits, Sentinel health/regeneration, hanging-entity and navigation-start fixes, registry identity preservation, and restored native speech parameters. |
| Removal and undo | ID/UUID/name/owner/world/entity targeting, ownership enforcement, temporary NPC snapshots, collision-safe/retryable undo and actual command failure propagation. |
| Permissions | Registered NeoForge nodes with correct names, real non-OP Paradigm grants/revocations, live contextual/dynamic permission checks, inherited group queries, selection permissions and local attachment lifetime; additional execution checks for permission-restricted flags. |

## Current remaining gaps

| Area | Still missing or unresolved after the follow-ups |
|---|---|
| Permission mutations | The 263 `manuadd` actions need a working GroupManager-to-Paradigm replacement/primary-group bridge. `PermissionWriter`, externally visible temporary grants and world/dimension-scoped group membership queries remain unavailable. Paradigm's own 2.4.2b CLI also rejects wildcard/Unicode permission arguments. |
| Quests | Quest service/progress/delivery, nine `questadmin` actions and six quest placeholder references remain without a replacement integration. |
| Saved items | Thirteen referenced `meta.internal` payloads remain undecoded. Original item IDs 6, 15 and 144 are absent despite 21 action references. |
| Dialogue presentation/configuration | Forty-one holographic-dialogue configurations and the enabled dialogue BossBar still lack implementations. Typewriter/ActionBar, incoming-chat restoration, remaining mounted-player policies and most message/config keys remain incomplete. |
| Dialogue authoring/actions | Original create/edit/delete/start/stop/reset/list/influence workflows, inline options/interruption actions and eleven additional original action verbs are still absent. |
| Native command surface | The missing 1.21.1 command list below still contains 37 `/npc` names/aliases (35 groups). Dedicated text/display/entity configuration and remaining parameters are not restored merely by having their trait classes or permission nodes. |
| Native behavior/API | Swimming switch application, live slime-size changes, tablist defaults, default player step height, item holograms, the full text parser, anvil-style text entry and 24 reference API event names remain open. |
| Sentinel | Full `/sentinel` administration, ranged combat/ammunition, richer target/ignore policies, guarding, damage/armor, avoidance and death/drop/XP behavior remain partial or missing. |
| Other command/service compatibility | Full Essentials healing, obsolete item-NBT/component and nested/plugin command semantics remain separate work. Two legacy shop identifiers and two offline recipient identities still need confirmation; the service bridge deliberately does not invent them. |
| Deployment/acceptance | Production deployment, migration/review of the old Interactions config/messages, physical client verification and complete modpack acceptance remain outstanding. The staged service-bridge manifest for `bc47082` predates removal and permissions work. |

The unresolved source identifiers are shops `金城银行存款` / `风巽贵金属积存赎回` and accounts `VerticalYeti503` / `Santoesia`. Their destinations/UUIDs have not been guessed.

Current validation: 58 real-provider/restart permission checks, 17 no-provider checks, 44 removal checks, 107 NPC assertions, 223 runtime probes, 40 dialogue unit cases and 131 ordinary tests all pass. Normal jars exclude the test probes. Production Citizens/Interactions hash checks in this batch still match the original audited deployment; no local repair has been deployed by this task.

The following sections preserve the September 13 01:47 detailed audit for source evidence. Its then-current implementation/hash table and then-open service/removal rows are historical; use the current delta/gaps above and linked follow-ups for today's disposition.

**Verdict: the local port is substantially safer and more capable than at the start of this work, but it still does not reproduce the complete Citizens/Interactions/Sentinel stack. Several configured old-server workflows remain unavailable. This is not merely a remaining client-testing problem.**

## Comparison boundaries

| Reference | Meaning |
|---|---|
| Starting implementation: `f8ede13` | The September 8 snapshot that committed implementation already present when the audit began, including the separate dialogue engine. It is the appropriate baseline for crediting new work. |
| Current implementation: `6d8de22` | Last implementation commit before this report. There are 27 subsequent commits after the starting snapshot, including repairs and acceptance-only changes. |
| Original Citizens: `d98e55016c2df8102f37732cd19168fe8dd27e46` | GitHub HEAD fetched on September 13; latest commit dated September 12. Four commits newer than the initial `e2998fd` comparison. [Upstream snapshot](https://github.com/CitizensDev/Citizens2/tree/d98e55016c2df8102f37732cd19168fe8dd27e46). |
| Original server stack | Citizens 2.0.32-b3208, Interactions 2.14.1, Sentinel 2.9.0-SNAPSHOT-b522, Quests 5.2.3, and the previously inventoried companion plugins/data. |
| Target | Minecraft 1.21.1 / NeoForge 21.1.248 for the most recent acceptance run. Later Minecraft-only features are classified separately. |

The source comparison includes all Java command declarations, not only `commands/NPCCommands.java`. The audit tool was corrected to accept Java's scalar syntax for array-valued annotations: `modifiers = "moveto"`. Both trees already contained that command. Consequently, the correct command counts are **193 upstream / 155 port**, rather than the old report's 192 / 154. This correction does not represent a newly implemented command or change the missing-command list.

The current implementation and the starting implementation both have **155 root/modifier pairs**, including **133 `/npc` pairs**. Upstream has 169 `/npc` pairs, of which 126 are shared. There are **43 missing upstream `/npc` names/aliases** and two missing waypoint debugging aliases. Seven port-only `/npc` pairs do not replace missing upstream capabilities. These are declaration counts, not a feature-completion percentage.

**Deployment is still at the original audited build.** Read-only SHA-256 checks show the production Citizens and Interactions jars have exactly the same hashes as on September 8, while both current local build hashes differ:

| Artifact | SHA-256 |
|---|---|
| Production Citizens | `73e82636cc6da1cdf534a6666b43d8553ee7458eb38b18bd685c45e3dd8a6673` |
| Local Citizens | `8463f36c7c7d9171ca1130ff1a28f37ef87709a8c6f618ace3b5eaad344b20c2` |
| Production Interactions | `3bc4bdff01f94473395e6e52d3dd5ca3c31e3d06a55c38623187d8712a12e113` |
| Local Interactions | `be6d35f42489557c7ebed2ee091b04b1c903a5d35de0df5a03e721fd1d80c852` |

Thus, “repaired” below means the local source/build. It does not mean those repairs are running on the production server.

The production `config/interactions/` directory also **does not contain `config.yml` or `messages.yml`**. It currently contains conversations, players, command aliases, item aliases and items. Deploying a repaired jar alone would therefore use fallback settings/messages rather than the old custom configuration; those two files still require deliberate migration and compatibility review.

## What this work added or repaired

| Area | At the starting snapshot | Current result | Main commits |
|---|---|---|---|
| Dialogue cooldowns and player files | Cooldown records were overwritten; cooldowns were per player rather than per conversation. | Conversation-scoped cooldowns persist with the original start-timestamp semantics. Unknown player-file data survives updates; malformed files are not silently overwritten. Relevant to 70 configured nonzero cooldowns. | `2f7c3ea` |
| Remembered dialogue predicates | Stored dialogue records were not consulted by conditions. | `%interactions_has_dialogue_*%` reads saved records. This does not implement Quests predicates or the complete PlaceholderAPI ecosystem. | `2f7c3ea` |
| Random dialogue | The first eligible line always won. | Eligible lines are randomized; failed conditions still filter them. Relevant to 151 configured random nodes. | `2f7c3ea` |
| Routing and manual progression | Conditional and terminal routing, borrowed options, and manual waiting were incomplete. | Conditional destinations, terminal `start_conversation`/`start_options`, cycle handling, `time: -1`, next buttons and configured NPC-click skipping work. Missing option destinations are rejected before actions. The old data has 2,296 conditional-dialogue entries and 1,270 line-level destination entries. | `6b9e114`, `06293bc`, `a51e771` |
| Option selection | Clicking was limited to choices 1–9; requirements could change after display. | The tenth option works, requirements are rechecked, and choices use displayed indices. MOVE/SCROLL navigation, a 200 ms repeat interval, wrap/clamp and sneak confirmation were added. Redraw does not repeat rewards. | `2f7c3ea`, `3aea1e3`, `f8e371f` |
| Dialogue entry and exit | Parsed proximity/movement/permission/air settings were not connected to runtime behavior. | Proximity entry/re-entry, positive start radii, zero-radius semantics, exact exit boundaries, permission/air gates and three NPC click modes are enforced. Relevant to 113 nonzero start radii and 12 movement-lock configurations. | `d4c1968`, `64833fc`, `fceb029` |
| Movement and input restrictions | Relevant global settings were ignored. | Session-owned horizontal walking/vehicle-driver locks; chat and command restrictions/whitelists; inventory, drop and main-hand input restrictions; configured mob targeting rules. Locks are released correctly and do not block internal rewards. This does not establish all modded vehicle/container or physical block interactions. | `f05cb4f`, `5121ab1`, `382af12`, `54df47c`, `660b7f9` |
| Dialogue presentation | Several formats were hardcoded or ignored; speaker headings were repeated; JSON was displayed literally. | Configured names, option layouts, selected rows, hover/click text, spacing and next controls are applied. JSON components retain supported styles/events; invalid JSON aborts before that line's payment/reward. | `3aea1e3`, `65f8fef`, `5779bb9` |
| Sounds and vanilla command roots | Underscores were indiscriminately replaced in sound IDs; Bukkit's `minecraft:` roots failed lookup. | Registry-based sound resolution fixes 4,065 note-block actions. Root resolution addresses 1,683 namespaced command occurrences without changing their arguments. Old-version command arguments remain a separate issue. | `2f7c3ea`, `ba84774` |
| Failure handling in dialogue actions | Failed removal, unknown commands or missing rewards could be followed by further rewards/actions. | Action/line preflight, combined affordability checks, observable command results and failure propagation stop the sequence and avoid incorrectly recording completion. Unavailable aliases now reject the action before payment instead of silently dropping it. | `b4c7ec4`, `a51e771` |
| Dialogue recipients and balance queries | `si`/`eco` could use the actor instead of the named recipient; balance queries fell through. | Explicit online recipients, balance queries, exact currency precision and provider exceptions are handled. Offline recipients are rejected. | `b4c7ec4` |
| Citizens native economy | Native command/shop monetary actions had no registered provider and mishandled payment/refund failures. | Optional Yuuniverse Economy provider, failure propagation, native shop compensation, both balance projections and exact decimal batch arithmetic. Actual Economy 0.4.1 ledger/restart checks passed. This is distinct from the still-missing legacy `shop` dialogue bridge. | `fb3bba1`, `36a901d`, `2280694`, `b92e5ca` |
| Sentinel health behavior | `healRate` was ignored; spawn health/invincibility transitions were incomplete. | Regeneration timing and one-hit-point healing, immediate maximum/current spawn health and both directions of invincibility changes are implemented. All seven old guards specify `healRate: 100`. | `d0078e3` |
| Native API defaults | API sneaking changed only the current entity; creation missed configured LookClose/armor-stand defaults. | Sneaking persists while unspawned and across respawns; default traits are attached correctly. | `c955d14` |
| Hanging NPCs and path initiation | Unsupported paintings/frames could disappear on vanilla hanging ticks; the deferred first path request could lose its ground-state prerequisite. | Hanging NPC tick behavior matches the upstream controller approach, with ordinary entities retained as controls. The first deferred navigation request initializes its required state at request time. | `5779bb9` |
| Registry-backed persistence | Custom painting/attribute identities lost namespaces; dotted attribute keys were treated as paths; missing providers could cause saved settings to be erased. | Full custom IDs, literal dotted keys and unresolved values are retained; explicit replacement/clearing still works. | `971e474`, `fbf0498` |
| Native NPC speech | `/npc speak` omitted its talker and upstream parameters. | Talker context, player/NPC targets, range selection, temporary bubbles and recipient-specific placeholders are restored. Existing speech events can cancel/modify delivery. | `6d8de22` |
| Acceptance infrastructure | A normal server exit could hide failures; entity queries could run before section loading completed. | Required completion markers, explicit failure detection, entity-section readiness and common fixture-relative timing. This is improved evidence, not an extra gameplay feature. | `d0078e3`, `5779bb9` and subsequent probe commits |

NPC creation, 200 old NPC identities, skin display, basic LookClose, ordinary navigation/waypoints, menus, the existing 95 traits and the initial dialogue engine were already present at the baseline. They must not be credited as newly implemented during these 27 commits.

## Remaining gaps affecting configured old-server workflows

| Priority | Remaining gap | Concrete current evidence / impact |
|---|---|---|
| P1 | Permission-group mutation bridge | Both the source seed and deployed aliases still map `manuadd` to `paradigm permissions group add {player} {2}`. This is not the installed Paradigm membership path. The old data contains **263** such actions. Correct command spelling alone would still need GroupManager replacement/primary-group semantics, rather than simply adding membership. Native group *queries* already exist; group mutation is the broken path. |
| P1 | Legacy dialogue shop entry | `shop` remains a blank alias: **58** actions cannot open the intended service. The native Citizens shop/payment fixes do not implement EconomyShopGUI dialogue access. The economy service having shop tables is not this integration. |
| P1 | Quests service and predicates | No replacement quest service was identified in the current installed inventory. There are **4 quest definitions**, **5 NPC delivery stages**, **9 `questadmin` actions**, and **6 `%quests_*%` references**. Quest progress, delivery and these predicates remain unavailable. |
| P1/P2 | Dialogue camera actions | `cam-server` is still a blank alias, affecting **4 parsed actions**. The old provider remains unidentified. A native Citizens spectate/camera command does not reproduce this separate scripted dialogue dependency. |
| P1 | Saved-item fidelity and missing definitions | `ItemLibrary` restores type, name, lore and written-book content, but does not decode **13 referenced `meta.internal` payloads**. IDs **6, 15 and 144** are absent from the original database despite **21 action references**. The latter is inherited bad data; preflight now protects payment but cannot supply the missing reward. |
| P2 | Interactions holographic dialogue | **41** conversations enable `hologram_dialogues`; the structure is still not loaded or rendered. Native Citizens text holograms and speech bubbles are separate capabilities. Configuration counts are not proof of what the original client actually rendered. |
| P2 | Interactions dialogue BossBar | The old global `boss_bar.enabled` is **true**, with BLUE / SEGMENTED_10 settings. No dialogue BossBar settings/runtime handling exists. This is a configured feature gap, separate from Citizens' native BossBar trait. |
| P2 | Remaining global configuration semantics | Mounted-player handling (`protocollib_player_already_mounted: BLOCK`) is not read. The configured autosave interval, broader message overrides and other presentation settings are not all implemented. The transport-specific ProtocolLib dependency itself does not need to be recreated, but its gameplay behavior needs an equivalent. |
| P2 | Complete Essentials healing/command compatibility | `heal` remains an instant-health effect alias, rather than the complete old command. One `minecraft:heal` action is not a vanilla command. Root normalization does not convert obsolete 1.20 item NBT/1.21 components, nested commands, or plugin-specific semantics. |

The service aliases no longer failing silently is an important safety improvement. It does **not** mean these services now work: affected local sessions can now stop at the unavailable action instead of continuing as though it succeeded.

## Remaining Interactions capabilities beyond the active data

The old plugin's actual `MainCommand` and `ActionUtils` bytecode were checked, in addition to the YAML. Current implementation supports the seven action verbs used by the active files; that is not the entire plugin.

| Capability | Current difference |
|---|---|
| Administration and authoring | The port registers `choose`, `skipdialogue`, `reload` and `status`. Original `create`, `edit`, `delete`, `start`, `stop`, `resetplayer`, `list` and `influence` workflows, including their editor inventories, are absent. NPC-triggered conversation entry does not replace a general administrative start command. |
| Additional action verbs | Original dispatch recognizes 18 verbs; the port recognizes 7. Missing native action forms: `remove_potion_effect`, `player_command`, `actionbar`, `playsound_resource_pack`, `stopsound`, `stopsound_resource_pack`, `firework`, `wait`, `wait_ticks`, `influence`, `send_to_server`. Some effects can be expressed as console commands, but their original action syntax is not implemented. The last action also needs an appropriate server-transfer integration. |
| Inline options and interruption actions | `options_in_dialogue` and `interrupt_actions` are absent from the model/loader/session. Neither is explicitly configured in the current 146-file snapshot, but both exist in the original plugin. Ending a port session does not run original interruption-action lists. |
| Typewriter and status displays | `write_dialogues`, ActionBar display and configurable dialogue BossBar/progress are absent. Typewriter and ActionBar are disabled in the inspected old config; BossBar is enabled. |
| Incoming-chat hiding/restoration | `hide_chat_while_in_conversation_type`, compatibility mode and `send_back_hidden_chat` are not read. Blocking outgoing chat is a different feature. The inspected hide mode is NONE, so not all of this affects the present configuration. |
| Selection/mount presentation | MOVE/SCROLL and sneak confirmation are implemented, but configurable confirmation action, old fake-mount/camera presentation and already-mounted policies are not completely reproduced. Modded/noncontrolling passengers remain outside current acceptance. |
| Custom defaults and influence | Configurable creation defaults and the original influence model/commands are not implemented. Retaining unknown player-file data does not make influence functional. |
| Message catalog | Only **8 of 226** keys from the old `messages.yml` are read by `DialogueMessages`. The missing keys include status/denial messages and editor text. This is a configuration-coverage count, not 218 distinct missing gameplay features. |
| Conditions and placeholders | Saved-dialogue and selected CheckItem/player predicates work. Complete external PlaceholderAPI expansions, including Quests, are not available; this is not a general placeholder compatibility runtime. |

Evidence: `Conversation.java`, `ConversationLibrary.java`, `Session.java`, `DialogueSettings.java`, `DialogueMessages.java`, `InteractionsMod.java`, `Actions.java`; `artifacts/legacy-interactions-maincommand-status.txt`, `artifacts/legacy-actionutils-status.txt`, and `artifacts/parity-status-2026-09-13/legacy-configuration-coverage.json`.

## Remaining native Citizens command surface

**37 missing `/npc` command names/aliases are relevant to Minecraft 1.21.1.** Collapsing the two synonymous pairs below gives 35 command groups. The table groups them for readability, not to imply their backend traits are all absent.

| Area | Missing command names |
|---|---|
| Text, display and effects | `text`, `bossbar`, `display`, `itemdisplay`, `textdisplay`, `interaction`, `potioneffect` |
| Passive/animal/entity settings | `allay`, `armadillo`, `axolotl`, `bee`, `camel`, `cat`, `fox`, `frog`, `goat`, `llama`, `mooshroom` / `mushroomcow`, `panda`, `parrot`, `polarbear`, `pufferfish`, `sniffer`, `snowgolem` / `snowman`, `tropicalfish`, `villager` |
| Other entity settings | `areaeffectcloud`, `boat`, `enderdragon`, `phantom`, `piglin`, `shulker`, `spellcaster`, `vex`, `warden` |

Most matching trait classes already exist and can load saved settings or be attached via `/trait`; the missing part is their dedicated configuration command. That still breaks one-to-one command compatibility. `InteractionTrait` itself also lacks a port counterpart. `/npc text` has a text backend but no original chat-based text editor.

Six more missing pairs concern later-version capabilities: `chicken`, `cow`, `pig` variants and `coppergolem`, `mannequin`, `sulfurcube`. They are not ordinary missing 1.21.1 entity features. `waypoints hpa` / `wp hpa` are missing debugging aliases; ordinary waypoints exist.

### Existing commands also have differences

| Command/group | Confirmed difference, beyond declaration presence |
|---|---|
| `remove` / `rem` | The port only distinguishes `all` from “remove selected”. Original ID/name, owner, world and entity-UUID targeting is missing. **With NPC A selected, `npc remove B` follows the current source path to destroy A, not resolve B.** This is a concrete data-loss risk from legacy syntax, not merely a missing help entry. It was identified by source inspection; no production delete was executed. |
| `skin` | Name lookup, clear and latest-update mode exist. Original URL/file import, texture/signature input, export, skull and Bedrock-related command branches are absent. Existing skin display should not be described as missing. |
| `armorstand` | Basic switches exist; body/head/left-right arm and leg pose flags are absent. |
| `create` | Item-provider input, timed temporary duration, unspawned/center/packet shortcuts are missing. The packet trait/backend exists and is already tested separately. |
| `list`, `select` | Registry/tag/expression filters and original selection-range/registry options are incomplete. Page, owner/type or name operations have some alternate syntax. |
| `mount` | UUID target syntax and original cancel/dismount flags are absent; ordinary mounting exists. The port's direct player `startRiding` path also differs from the upstream Controllable check/delegation. |
| `lookclose` / `look` | Random/head-only/per-player/navigation toggles have short-flag equivalents. Linked-body, target filtering/NPC targeting, random delay/ranges/switch controls and complete original long-flag syntax are not exposed. This is not an assertion that all their persisted trait fields are ignored. |
| `pose` | Save/assume/remove/default exist. Mirroring and explicit yaw/pitch command controls, with the original flag variants, are incomplete. |
| `anchor`, `chunkload`, `collidable` | Cursor-target anchor saving, temporary chunkload and fluid-push configuration flags are missing. Basic anchors, chunk tickets and collision controls exist. |
| `spawn`, `tp`, `tphere` / `move` | Name/legacy option support and exact/front/cursor/centering semantics differ. The port's `tphere` declares `-c` but its body does not inspect it. Ordinary coordinate movement exists through `moveto`, which the initial scanner had omitted from both inventories. |
| Remaining syntax differences | Explicit `follow --enable` and `powered --set` are absent while toggle/cancel operations exist. `name -h` has a positional hover alternative; `horse --colour` is an alias difference because `--color` works; `home` supports a current-location path but not every original flag/duration spelling. These should not be counted as missing whole behaviors. |

The raw flag scan also reported `minecart --item`; inspection showed the upstream parameter is unused, so this is **not** counted as a missing implemented feature. Command/flag counts alone are insufficient to determine behavior.

Evidence: `neoforge/src/main/java/net/citizensnpcs/commands/NPCCommands.java`, current upstream methods, and the adjacent [current command inventory](npc-parity-status-2026-09-13/commands.csv). The machine flag candidates in `artifacts/parity-status-2026-09-13/native-surface-delta.json` require the manual qualifications above.

## Remaining native behavior and API differences

| Capability | Current finding |
|---|---|
| Swimming control | `/npc swim` writes/toggles `NPC.Metadata.SWIM`, but there is no runtime consumer equivalent to the upstream swim update. Vanilla aquatic behavior or swimming path examiners do not make this switch effective. Source-confirmed gap; no new water/client scenario was run for this report. |
| Slime/magma-cube size changes | Current `SlimeSize.setSize` changes the stored field only; application happens in `onSpawn`. The command does not call it afterward. Upstream's September 12 change now calls `onSpawn` immediately. Thus an already-spawned entity retains its old size until respawn in the port. The existing size-at-spawn assertion does not cover this change-after-spawn path. |
| Player-list/tablist defaults | `shouldRemoveFromPlayerList` and `shouldRemoveFromTabList` hardcode a true default instead of applying upstream global defaults. Per-NPC metadata/skin packet paths exist, so this is a default-configuration gap rather than total absence of tablist support. |
| Default player step height | Upstream explicitly sets step height to one for player/horse NPCs without an overriding attribute. The matching initialization is absent from the port. The actual combined modpack/client effect has not been measured in this audit. |
| Item holograms | `<item:...>` is still passed to text rendering. Ordinary text holograms and restored temporary speech bubbles work, but do not implement item holograms. |
| MiniMessage | Native `TextParser` lacks item/entity hover, insertion, selector, score, keybind, translation tags and full resolvers. JSON dialogue support is a separate path; it does not complete this parser or convert all 1.20 hover/item payloads. |
| GUI text entry | String entry uses the chat fallback, not the original anvil input interface. Rename/string-setting functionality exists; the original in-inventory interaction differs. `NPCConfigurator`'s throwing private constructor is not an inert public GUI—the public constructor is implemented. |
| Citizens API event contract | The checked CitizensAPI reference has **24 absent same-name events**, listed below. Some native events serve related purposes, but the original Citizens-specific contract is not exposed one-to-one. Absence of a wrapper is not proof that ordinary damage or movement never occurs. |

Missing reference API event classes:

`CitizensDeserialiseMetaEvent`, `CitizensEnableEvent`, `CitizensGetSelectedNPCEvent`, `CitizensPreReloadEvent`, `CitizensReloadEvent`, `CitizensSerialiseMetaEvent`, `CommandSenderCloneNPCEvent`, `CommandSenderCreateNPCEvent`, `NPCCollisionEvent`, `NPCCombustByBlockEvent`, `NPCCombustByEntityEvent`, `NPCCombustEvent`, `NPCDamageByBlockEvent`, `NPCDamageByEntityEvent`, `NPCDamageEntityEvent`, `NPCLinkToPlayerEvent`, `NPCMoveEvent`, `NPCPistonPushEvent`, `NPCPushEvent`, `NPCSelectEvent`, `NPCUnlinkFromPlayerEvent`, `NPCVehicleDamageEvent`, `PlayerCloneNPCEvent`, `PlayerCreateNPCEvent`.

Platform adapters such as Bukkit/Folia scoreboard classes, NMS wrappers and ProtocolLib hooks are not counted as missing gameplay merely because same-name files are absent. Bukkit plugin binaries also do not become compatible by copying class names into a NeoForge API.

## Sentinel remains a partial port

The current guard supplies melee pursuit/retaliation, group/held-item player targeting, squad aggro, respawn, greetings and the newly repaired health behavior. Much of that melee engine was already present at the start.

Still absent are the original `/sentinel` command suite, ranged combat/projectile/accuracy/ammunition behavior, automatic weapon switching, full target/ignore rule types, guarding/follow-protection policies, customizable damage/armor, avoidance/runaway behavior, enemy-drop/death-XP handling and other preserved-but-unconsumed settings. Source targeting currently scans players and matches group/held-item rules; passthrough fields do not implement the other targeting categories.

The seven saved guards have zero historical projectile counters and several default/off values. That limits evidence of active dependence; it does not establish full Sentinel equivalence. `damage`/`armor` are -1 in the inspected snapshot, while settings such as `enemyDrops: false`, `realistic: true`, `safeShot: true` and the guard-policy fields still require semantic reconciliation. NPC inventory/equipment or vanilla attacks do not by themselves reproduce all Sentinel controls.

## Disposition of the original 18 findings

| Initial finding | Current disposition |
|---|---|
| F01 command roots | Root resolution repaired; old arguments, nested/plugin commands remain separate. |
| F02 shop/quest/camera | Still unavailable; changed from silent dropping to explicit preflight failure. |
| F03 GroupManager mutation | Still open. |
| F04 failed action sequences | Preflight/abort and native shop compensation repaired. A general atomic transaction across arbitrary services/commands is not provided or established as an original-plugin guarantee. |
| F05 progress predicates | Interactions saved-dialogue predicates repaired; Quests predicates remain open. |
| F06 cooldown persistence/scope | Repaired and covered. |
| F07 entry/locks/global protections | Much repaired; unsupported global settings/mount and client/modded interaction cases remain. |
| F08 sound IDs | Repaired and covered. |
| F09 random dialogue | Repaired and covered. |
| F10 holographic conversations | Still open. |
| F11 option limits/input | Tenth choices and MOVE/SCROLL/sneak selection repaired; original inline/camera presentation remains incomplete. |
| F12 saved items | Lossless internal payload migration and missing original definitions remain open; failures now avoid payment. |
| F13 native economy provider | Provider/payment/refund wiring repaired and real-ledger/restart checks passed. |
| F14 command semantics | Explicit online recipients and balance queries repaired; healing/general plugin semantics remain partial. |
| F15 Sentinel | Health/regeneration repaired; complete combat/administration parity remains open. |
| F16 native commands | Missing command list remains unchanged; speech parameters repaired; further parameter/behavior gaps confirmed. |
| F17 native behavior/API | Sneaking/default traits and later hanging/navigation/persistence issues repaired; item holograms, parser, API/config differences remain. |
| F18 acceptance evidence | Fixture readiness and failure detection repaired; latest isolated suite passes. Full server/client acceptance remains outstanding. |

Two earlier interpretations should not be counted as missing features or extra repairs: NPC 35's unindexed `speech: chat` is a retired field that was already ignored correctly, and resetting an attribute to its registered default matches the old API. The proposed entity-type-default reset was withdrawn. Skin layers, packet NPCs and command registration also have implementations despite stale TODO text.

## What has been validated, and what has not

Existing evidence for the current implementation includes:

- 124 normal tests, zero failures/errors/skips; normal release build passed (`artifacts/repair-speech-final248.log`).
- 107 NPC scenario assertions and 223 runtime probes passed on isolated NeoForge 21.1.248 (`artifacts/repair-speech-runtime248-connection.log`).
- 37 dialogue audit tests passed at the last dialogue implementation change (`artifacts/repair-json-dialogue-tests.xml`).
- Actual Economy 0.4.1 provider/ledger checks and a second-process balance persistence check passed (`artifacts/repair-real-economy248.log`, `artifacts/repair-real-economy-restart248.log`).
- Release/source jars exclude opt-in runtime audit code.

This report performed fresh source/annotation/YAML/installed-jar checks and reused those recorded execution results. It did not rerun the entire suite, connect a real Minecraft client, modify production configuration, or deploy jars. New source findings such as remove-argument targeting and change-after-spawn slime size are not claimed to have been reproduced against production.

Remaining acceptance work includes real-client skins/mounts/seats/look rotation, GUI/container prediction, hologram rendering, dialogue input while riding, full installed-modpack integration and the still-unimplemented service workflows. Passing selected tests is not a claim that all 155 command pairs, all fields of 95 traits, all 146 conversations or all old plugins are equivalent.

The old directory also contains non-NPC server capabilities—CoreProtect, CurveBuilding, Vivecraft integration and others—that Citizens is not a replacement for. WorldEdit and economy counterparts have separate acceptance obligations. No new claim of complete old-server replacement is made by this NPC audit.

## Reproduction and evidence

The consolidated report is based on the corrected `tools/audit_npc_parity.py` inventory, the `f8ede13..6d8de22` diff, fresh upstream HEAD, direct current-source inspection, and the old plugin bytecode already retained in `artifacts/`.

- `artifacts/parity-status-2026-09-13/summary.json`: refreshed old-data/installed-server counts.
- `artifacts/parity-status-2026-09-13/native-surface-delta.json`: before/after declarations, missing API event names and raw flag candidates.
- `artifacts/parity-status-2026-09-13/dialogue-structure.json`: actual keys used at conversation/node/line/option levels.
- `artifacts/parity-status-2026-09-13/legacy-configuration-coverage.json`: supported/unsupported legacy global/message keys.
- `artifacts/legacy-interactions-maincommand-status.txt` and `artifacts/legacy-actionutils-status.txt`: original administration and action dispatch.
- `walkthrough.md`: dated implementation decisions, failures and validation history.

Suggested repair order follows current impact: correct dangerous command-target semantics and the permission bridge; connect shop/quest services and resolve saved-item data; restore configured dialogue presentation/mount policies; then complete the wider original command/action/API surface and perform connected-client/modpack acceptance.
