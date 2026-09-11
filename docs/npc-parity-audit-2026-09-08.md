# Citizens / Yuuniverse NPC parity audit

Audit date: 2026-09-08, UTC+08:00. **Acceptance result: FAIL — feature parity and error-free operation are not established.**

This records the initial audit of the working tree, old Arclight plugin snapshot, and deployed NeoForge installation. Production jars and data have not been replaced. The findings below describe the audit baseline; subsequent repairs are tracked separately in the next section and in `walkthrough.md`.

## Repair status — 2026-09-10, UTC+08:00

- `2f7c3ea`: persistent conversation-scoped cooldowns (legacy values are **start timestamps**, not expiry times), lossless player YAML updates, saved-dialogue predicates, randomized dialogue selection, option requirement rechecks, choices beyond nine, and registry-based sound resolution.
- `ba84774`: Bukkit vanilla command roots resolve against the native dispatcher without rewriting arguments. This does not convert 1.20 NBT or nested commands.
- Action execution now preflights a complete action list and a line's delayed actions before consuming its immediate payment. Missing saved items, unavailable economy, blank aliases, unknown/incomplete commands, and combined insufficient item costs fail before execution. Runtime failures stop remaining actions and end the session without marking completion. Option actions execute from the server tick so command outcomes are observed outside the selecting command's queue.
- Saved-item and economy commands respect an explicitly named online recipient; balance queries use the economy API. Currency values must be non-negative and exactly representable at the configured precision. Economy exceptions propagate instead of masquerading as success.
- Sentinel now consumes `healRate`, restores one nominal hit point after the configured interval, clamps to maximum health, and disables regeneration for non-positive rates. Spawn applies configured maximum/current health immediately and sets invincibility in both directions. These behaviors were checked against the old Sentinel jar, including its strict elapsed-time comparison and one-hit-point increment.
- The knockback fixture now waits for both entities to become queryable, checks both damage calls succeeded, measures coordinate displacement rather than selector-box intersection, and requires both actors to survive. Tracking showed entity sections becoming visible later than their chunks; `addFreshEntity` success alone did not prove readiness for the old scheduled checks. The control moved approximately 1.78 blocks while the disabled actor stayed still.
- Runtime validation passes on **both NeoForge 21.1.233 and 21.1.248**: 107 NPC scenario assertions, 10 dialogue action probes, and 10 Sentinel probes. The runtime task now fails on failed or missing assertions instead of treating a clean server exit as acceptance. Full production-modpack/client acceptance remains outstanding.
- Native API repairs: `NPC.setSneaking` now delegates to the persistent `SneakTrait`, including while unspawned; registry creation restores upstream's armor-stand trait and configured default LookClose behavior. Eight dedicated-server API probes cover these paths on 21.1.248. This does not close the other API/event or hologram gaps listed in F17.

- Native economy: commits `fb3bba1`, `36a901d`, and `2280694` connect the optional provider, abort rejected payments, compensate completed native shop actions, restore both balances, and preserve decimal batch arithmetic. A separate fixture with the actual Economy 0.4.1 jar passes 10 provider/ledger checks, including frozen/missing accounts and rejection without balance changes. A second server process confirms the final 9.7 balance survives restart. These checks do not establish every command/UI entry point or cross-service atomicity. See `tools/economy-runtime-audit/README.md` and `artifacts/repair-real-economy248.log`, `artifacts/repair-real-economy-restart248.log`.

- Dialogue radius repair: proximity starts now use the configured positive radius with a once-per-second scan and an entry latch; zero disables proximity starts. Click and proximity paths share cooldown, permission and air-start checks. Session distance exits now respect zero as unlimited and the exact nonzero boundary. The old Interactions 2.14.1 bytecode supplies these semantics. Four real-session boundary/removal probes and eight controller-level FakePlayer probes pass. The latter cover named conversation entry, outside-radius rejection, active-session preservation, staying/re-entry behavior, missing permission and disabled air starts. Connected-client input/network behavior and granted permissions/modded-ground variants still need acceptance. Movement locking and other configuration gaps remain open.

- Movement restriction now enforces block_movement for unmounted player movement packets after vanilla validation and pending-teleport checks. It rejects horizontal displacement, allows rotation and vertical-only movement, and releases the session-owned lock at session end. Thirteen real packet-handler probes pass, including normal movement after release, replacement-session ownership and a controlling player riding a boat. Vehicle packets now also reject horizontal displacement after vanilla verifies the controlling passenger and tracked vehicle, with rotation and vertical-only movement preserved. Noncontrolling passengers, modded vehicles, camera/selection controls and connected-client visual behavior remain open; this is not full movement/cutscene parity.

**Limits:** preflight plus failure propagation is not an atomic transaction spanning inventory, arbitrary commands, and external services. Earlier successful external side effects cannot be rolled back by this batch, and effects across separate dialogue lines are not transactionally grouped. Offline recipients are explicitly rejected rather than redirected to the actor. The separate dialogue economy success path still needs real-mod acceptance; native MoneyAction payment/refund and persistence have now passed the real-mod checks below. The permission, shop, quest, camera, metadata, configuration, native trait-command and other open parity findings remain acceptance gates. No production deployment has been performed.

Run dialogue tests with `-I ../tools/audit-tests.gradle`; run dedicated-server probes with `-I ../tools/runtime-audit.gradle runServer` from `neoforge/`, after preparing the isolated `artifacts/audit-server` fixture. The runtime probe source is added only by that init script and must not be packaged into release jars. Run the normal build afterward to remove the opt-in sources from the outputs.

For the deployed NeoForge version, pass `'-Pneo_version=21.1.248'` as a quoted PowerShell argument. The runtime init script overlays the versioned knockback functions onto the existing isolated `npctest` datapack and checks every marker listed in `tools/runtime-audit-expected.txt`. Diagnostic traces and run output are in `artifacts/repair-knockback-*.log`, `artifacts/repair-guard-acceptance.log`, and `artifacts/repair-guard-neoforge248.log`.

## Baselines and evidence

| Input | Observed version / identity |
|---|---|
| Project target | Minecraft 1.21.1, NeoForge **21.1.233**, Java toolchain 21; `neoforge/gradle.properties` |
| Project mod | `2.0.43-neoforge-SNAPSHOT` |
| Working tree base | `a60bfb30245d4d085dc23e06fe3c293533571daf`, with pre-existing uncommitted changes, including the dialogue source set |
| Upstream fetched during audit | [CitizensDev/Citizens2 at e2998fd](https://github.com/CitizensDev/Citizens2/tree/e2998fd5fb8486b2551c1bcd366e37c263a6b35f) |
| Bundled upstream source baseline | `d0beeec`; the repository's `main/` is an older reference, not current upstream |
| Old server | `C:/Users/ben_l/Saved Games/Yuuniverse 1.20.1 Shenyang/Yuuniverse 1.20.1 Shenyang/plugins` |
| Old plugin versions | Citizens 2.0.32-b3208; Interactions 2.14.1; Sentinel 2.9.0-SNAPSHOT-b522; Quests 5.2.3 |
| Current server | `E:/yunniverse-server`; running Java process arguments select **NeoForge 21.1.248** |
| Current companions | Yuuniverse Economy 0.4.1-SNAPSHOT; Paradigm 1.21.1-2.4.2b; Yuuniverse Interactions |

The Citizens deployed jar and local build have identical SHA-256:
`73e82636cc6da1cdf534a6666b43d8553ee7458eb38b18bd685c45e3dd8a6673`.
Interactions' deployed SHA-256 is `3bc4bdff01f94473395e6e52d3dd5ca3c31e3d06a55c38623187d8712a12e113`, while the local build is `88e8074ad8658736b81ad4772f20b617b07f63033d2f204f8d4f5f4ca0e81fc8`. Every ZIP entry name and uncompressed entry body is identical: this is a container difference, not a code difference.

The target ranges currently permit more than the tested patch version (`minecraft_version_range=[1.21.1,1.22)`, `neo_version_range=[21.1.0,)`). Those declarations do not prove compatibility with every admitted version. The isolated runtime tests below ran on 21.1.233, not the full production 21.1.248 modpack.

## What did carry across

- The old snapshot has **200 NPCs**; deployed saves contain **209**. Every old ID exists and retains its UUID. This establishes identity continuity, not successful spawning/rendering of every NPC.
- **95 trait classes** are registered. Of the 18 keys occurring under old `traits`, 17 have directly identified registered counterparts. The remaining legacy scalar `speech: chat` on NPC 35 has no registered trait under that name; do not count the presence of the new speech API as proof that this old selector is consumed.
- All **146 conversation filenames** are present on the deployed server, with **145 byte-identical files**. The differing file is `志愿者弗拉基米尔·王铁柱.yml`; its difference is not classified as a migration failure. Two source files have empty trigger lists (`nitrolife.yml`, `卡琉佩站志愿者.yml`), consistent with the historical 144 loaded conversations.
- The real dialogue action inventory has seven verbs, all with switch branches in `Actions`: `player_command_as_op`, `console_command`, `title`, `teleport`, `playsound`, `give_potion_effect`, `remove_item`. Branch presence is not semantic equivalence.
- Source review finds the earlier player-info packet ordering, chunk lifecycle, fake-player chunk anchoring, and fake-player inventory synchronization fixes in the current tree. Existing tests exercise portions of movement, equipment, traits, templates, shops, and reload behavior.
- The current economy SQLite database was opened with `mode=ro` and `query_only=ON`. It contains **40 `system_shop` rows, 1,545 `system_shop_item` rows, and 100 `coordinate_shop` rows**. This is useful migration evidence, not NPC entry-point or transaction acceptance.

## Blocking findings

Priorities: **P1** blocks NPC gameplay, transactions, or migration acceptance; **P2** is a definite feature/configuration mismatch or a material verification gap. Counts are parsed YAML values/action entries, not raw-text matches including comments.

| ID | Priority | Finding and impact | Evidence / required correction |
|---|---|---|---|
| F01 | P1 | **Bukkit namespaced command entry points are passed through unchanged.** Old dialogues contain 1,683 `minecraft:` command actions, including 562 `minecraft:give`, 394 `minecraft:setblock`, 383 `minecraft:clear`, and 334 `minecraft:fill`. | `Actions.java:178–195` checks the literal root and dispatches unchanged. Isolated server lookup found `give` and `setblock`, but not `minecraft:give` or `minecraft:setblock`. Implement an explicit command compatibility layer, then validate arguments too. One `minecraft:heal` is not a vanilla command at all. Production mods may add aliases; that was not established by this isolated check. |
| F02 | P1 | **Shop, quest, and camera commands are intentionally discarded.** | `CommandAliases.java:35–41` and deployed `command-aliases.yml`: `shop: ''` (58 actions), `questadmin: ''` (9), `cam-server: ''` (4 parsed actions). Connect real services or report the feature unavailable; blank aliases are not implementations. Raw historical counts of five camera references were not semantic action counts. |
| F03 | P1 | **The GroupManager bridge names the wrong Paradigm command path.** 263 `manuadd` actions use the deployed template `paradigm permissions group add {player} {2}`. | Inspected installed Paradigm bytecode: `Reload` registers `PermissionCommands` directly under `paradigm`; it builds `permission` and `group` branches, with membership under `group user add <player> <group>`. `group add` creates a group. The configured path is not this API. Also establish replacement/primary-group semantics; appending group membership does not reproduce GroupManager `manuadd`. Do not rewrite the already separately maintained permission system merely to hide this call-site defect. |
| F04 | P1 | **Payments and rewards are not a transaction.** A failed item removal, failed economy call, unknown command, or missing reward does not abort subsequent actions. | `Actions.runAll:47` catches exceptions and continues; `remove_item:73` discards `CheckItem.evaluate`'s yes/no result. `Economy.handle` logs invocation failure but returns handled; callers cannot distinguish success. Validate dependencies before consuming assets and propagate failure; use a transaction/compensation strategy for multi-service exchanges. |
| F05 | P1 | **Saved dialogue progress has no reader in the dialogue decision path.** Story predicates cannot resolve. | `ProgressStore.hasSeen:91` has no callers. `Conditions.resolve` only resolves checkitem/player placeholders. Four `%interactions_has_dialogue_*%` references and six `%quests_*%` references cannot be evaluated. `Session.show` records progress, but `advance` does not consult it; loading 57 old progress files is not restoring their gameplay behavior. |
| F06 | P1 | **Cooldown records are erased and cooldown scope is wrong.** | `ProgressStore.saveDirty:119` writes `cooldowns: []`. `InteractionsMod:55,116,124` stores one cooldown per player, not per conversation; a long cooldown from one NPC blocks unrelated NPCs and is lost on restart. 70 conversation files specify nonzero cooldowns. The opt-in preservation test reproduces record loss. |
| F07 | P1 | **Movement lock / automatic proximity / global conversation protections are absent.** | `ConversationLibrary:114–121` reads `blockMovement`, `startRadius`, `requiresPermission`, `canBeStartedOnAir`; no runtime consumers enforce them. Real files include **12 movement locks** and **113 nonzero start radii**. The global Interactions config disables player commands and mob damage during dialogue; the new engine never loads this config and has no matching command/damage handlers. All current `requires_permission` values are false, so that omission is a capability gap rather than an active permission bypass in this snapshot. |
| F08 | P2 | **4,065 note-block sound actions resolve to nonexistent IDs.** | `Actions.soundId:97` replaces every underscore with a dot. Correct registry IDs are `block.note_block.pling` and `block.note_block.bell`; 3,492 pling + 573 bell actions are affected. Both opt-in tests fail against the actual method. Use registry-backed mapping that preserves internal underscores. |
| F09 | P2 | **Random dialogue is deterministic.** | `Session.advance:89–101` takes the first passing line then skips the rest if `randomDialogue` is true. There is no random choice. **151 nodes** set `random_dialogue: true`. |
| F10 | P2 | **Enabled holographic dialogue is rendered only as chat.** | **41 conversations** have `hologram_dialogues.enabled: true`. The loader does not parse the structure; `Session.show` sends chat. The historical assertion that all were disabled is contradicted by the current source snapshot. Citizens' own `HologramTrait` is a separate capability and does not supply this missing dialogue renderer. |
| F11 | P2 | **The tenth visible option cannot be clicked.** | `InteractionsMod:189` restricts `/interactions choose` to 1–9, while `Session` renders all options. `乞丐.yml` and `阿盖.yml` each contain ten options in the first node. Typing `10` through the chat handler can work, so this is specifically a clickable-choice failure. Remove the arbitrary limit and test both input routes. MOVE/SNEAK selection from the old config is also not implemented. |
| F12 | P1 | **The saved-item path is not lossless, and some reward IDs are undefined in the source itself.** | 140 ItemEdit definitions, 114 referenced IDs. **13 referenced definitions have `meta.internal`**, which `ItemLibrary.build` counts but never decodes. IDs **6, 15, 144** are referenced in **21 actions** but absent from the old database; this is an inherited data defect, not automatically a newly introduced bug. Their reward paths must fail before payment. Item names/lore/books being restored does not restore arbitrary item NBT/components. |
| F13 | P1 | **The separate Interactions economy bridge does not connect Citizens' native monetary features.** | `MoneyAction`, `CommandTrait`, etc. use `net.citizensnpcs.api.util.EconomyProvider`. Repository searches find its setter definition but no registration in Citizens or the companion economy source. `Economy.java` only serves dialogue `eco` actions; it does not install this provider. Native NPC money costs/rewards therefore remain unavailable unless an additional runtime adapter is supplied. No old NPC has `shop` in this snapshot, so this chiefly blocks full upstream-feature acceptance. |
| F14 | P2 | **Other command semantics are reduced.** | Dialogue `/balance` is not implemented by `Economy.handle` (it expects a mutation verb and numeric amount), so seven calls fall through to external command lookup. `heal` is replaced with an instant-health effect, which does not reproduce all Essentials healing behavior. `si give` and `eco` use the acting player rather than resolving arbitrary explicit target arguments. The current data mostly uses `%player%`; this is also an API/general-capability gap. |
| F15 | P2 | **Sentinel is a subset, even for melee behavior.** | `SentinelTrait` documents ranged combat as absent. All old projectile counters are zero, but that does not establish that melee behavior is complete. All seven saved guards have `healRate: 100`; this setting is neither persisted nor executed by the port. Damage/armor customization and several protection/targeting options also lack corresponding fields. Stats passthrough is not an implementation of those behaviors. |
| F16 | P2 | **Native Citizens command surface is incomplete.** | See the command inventory below. Presence of 95 traits does not expose their specific command parameters. `/npc text` is explicitly absent, and many versioned trait commands have no port declaration. |
| F17 | P2 | **Remaining native Citizens behavior/API gaps are real, but must be separated from stale TODOs.** | `HologramTrait:565` renders `<item:...>` as text; `CitizensNPC.setSneaking:303` changes only the current entity rather than `SneakTrait` persistence (the `/npc sneak` command itself uses the trait and is not this bug); `CitizensNPCRegistry:71` does not attach LookClose according to `npc.default.look-close.enabled`. There are 24 absent same-name API event classes. Skin-layer TODOs are not proof of failure: `EntityHumanNPC` already enables all skin layers via client information. |
| F18 | P2 | **Regression evidence does not justify an error-free claim.** | Isolated run 1: 103/106 assertions passed; run 2: 105/106. Failures concern knockback fixture survival/loading. Both server processes stopped normally. Current production `latest.log` also contains invalid-item errors involving `minecraft:stored_enchantments` with `null` keys; no attribution to Citizens is established. Investigate item migration and fixture validity separately. |

The economy reflection methods now match the inspected companion source (`credit`, `debit`, `setBalance`, `balance`, `defaultCurrency`). The older session note that the method shapes did not match is therefore stale. This improves structural compatibility, but does not prove online transactions, account initialization, failure handling, or atomic exchanges. Millisecond-based action keys also do not provide stable retry identity for a dialogue transaction.

## One-to-one Citizens inventories

[commands.csv](npc-parity-2026-09-08/commands.csv) lists every extracted root/modifier pair, its upstream source, and its port source. It scans **all Java sources**, including `trait/versioned`, rather than only `commands/NPCCommands.java`. [classes.csv](npc-parity-2026-09-08/classes.csv) lists every upstream main-module Java file against same-basename port sources; platform replacements and renamed classes require review. These are declaration inventories, not percentages of functioning features.

- Current upstream has 192 root/modifier pairs (168 under `npc`); the port has 154 (132 under `npc`, including aliases/additions).
- **43 upstream `/npc` pairs are absent; 125 are shared.** Seven port-only `/npc` pairs do not compensate for missing capabilities.
- Missing on the 1.21.1-relevant surface: `allay`, `areaeffectcloud`, `armadillo`, `axolotl`, `bee`, `boat`, `bossbar`, `camel`, `cat`, `display`, `enderdragon`, `fox`, `frog`, `goat`, `interaction`, `itemdisplay`, `llama`, `mooshroom`, `mushroomcow`, `panda`, `parrot`, `phantom`, `piglin`, `polarbear`, `potioneffect`, `pufferfish`, `shulker`, `sniffer`, `snowgolem`, `snowman`, `spellcaster`, `text`, `textdisplay`, `tropicalfish`, `vex`, `villager`, `warden`.
- Six other missing pairs need version qualification: `coppergolem`, `mannequin`, `sulfurcube` concern entities absent from 1.21.1; `chicken`, `cow`, `pig` expose later variant functionality. Do not label impossible later-version entity/variant features as ordinary 1.21.1 port regressions.
- `waypoints hpa` / `wp hpa` are absent debugging commands, not evidence that ordinary waypoint pathfinding is absent.
- The bundled `main/` baseline already misses the same set except the newer upstream `interaction` command. Thus these omissions cannot be explained solely by upstream changes after the port. The latest Interaction trait controls a type that does exist in Minecraft 1.21.1.

[old-traits.csv](npc-parity-2026-09-08/old-traits.csv) ties each old saved trait key to its port file and registration. Common behavior needing client acceptance includes 200 skin traits, 142 LookClose traits, 35 mounts, 28 seats, 20 scoreboard traits, 15 inventories, 9 waypoint routes, 7 guards, and 4 sleeping NPCs. A round-trip/source match is not a test of passengers, head rotation, skins, or menu transactions on a real client.

The 24 missing API event class names are:
`CitizensDeserialiseMetaEvent`, `CitizensEnableEvent`, `CitizensGetSelectedNPCEvent`, `CitizensPreReloadEvent`, `CitizensReloadEvent`, `CitizensSerialiseMetaEvent`, `CommandSenderCloneNPCEvent`, `CommandSenderCreateNPCEvent`, `NPCCollisionEvent`, `NPCCombustByBlockEvent`, `NPCCombustByEntityEvent`, `NPCCombustEvent`, `NPCDamageByBlockEvent`, `NPCDamageByEntityEvent`, `NPCDamageEntityEvent`, `NPCLinkToPlayerEvent`, `NPCMoveEvent`, `NPCPistonPushEvent`, `NPCPushEvent`, `NPCSelectEvent`, `NPCUnlinkFromPlayerEvent`, `NPCVehicleDamageEvent`, `PlayerCloneNPCEvent`, `PlayerCreateNPCEvent`.
This is a source-level contract gap against the local CitizensAPI reference, not a claim that native NeoForge damage events are absent. Bukkit plugin binaries cannot be made compatible merely by retaining Java class names with different platform types.

## Entire old plugin directory: NPC-related coverage

All 21 enabled-root plugin jars were inventoried. Folders without an enabled jar are not proof of a running plugin; disabled jars under `暂不启用` were not counted as active capabilities.

| Old plugin / dependency | Current counterpart and NPC relevance | Assessment |
|---|---|---|
| Citizens | Citizens NeoForge | NPC identities retained; native surface and runtime gaps above |
| Interactions | Separate `yuuniverse-interactions` jar | Partial engine; F01–F12 affect actual data |
| Sentinel | Built-in `SentinelTrait` | Partial seven-guard migration, not full Sentinel |
| Quests | No equivalent quest service identified among installed mods | Four quest definitions exist in `Quests/storage/quests.yml`; five NPC delivery stages and `NPCDestinationsQuestsModule-2.0.jar` add dependencies beyond nine admin commands. Quest predicates/progress/delivery must be migrated, not replaced with a command no-op |
| ItemEdit | `ItemLibrary` / items.yml | Saved-item subset; 13 internal blobs ignored, three referenced IDs undefined |
| PlaceholderAPI | Custom `CheckItem`, `Text`, `Conditions`, Citizens placeholders | Only selected expansions; quest and dialogue-progress predicates missing |
| GroupManager | Paradigm permission/group system plus Citizens group resolver | Installed public API structurally available; dialogue mutation alias wrong. Current permissions must be reviewed independently of NPC identity migration |
| EssentialsX | Paradigm for general commands; economy mod for money | Dialogue `eco` bridge exists; heal/balance/command compatibility incomplete |
| EssentialsXSpawn | Paradigm/general spawn facilities | No direct NPC dialogue call identified; full spawn/login behavior not certified |
| GemsEconomy | Yuuniverse Economy | Four-currency architecture and migrated database; NPC transaction integration still needs acceptance |
| EconomyShopGUI Premium | Economy system-shop service | Data present; all 58 NPC `shop` actions dropped. System-shop presence is not dialogue access |
| QuickShop | Economy coordinate shops | 100 rows present; no direct dialogue command found. Stock, ownership, and physical inventory acceptance belongs to companion service |
| TimeIsMoney | Economy time-payout service | Source and migration history exist; ongoing payout correctness not proven by an NPC audit |
| Vault | Separate economy/permission adapters | No Bukkit runtime; Citizens monetary provider remains unwired, while group resolver exists |
| ProtocolLib | Native packets / chat UI | A mechanism replacement; it does not automatically restore holograms, MOVE/SNEAK selection, or camera behavior |
| PacketEvents | Native packets / companion services | No direct NPC command found; not a drop-in API replacement |
| CoreProtect CE | No equivalent established in installed inventory | General rollback/audit capability, not supplied by Citizens; requires separate server acceptance |
| CurveBuilding | No equivalent established | Building tool; no NPC dependency identified |
| Item resource handling: ResourceHack | Resource-pack / current mod assets | No direct NPC command identified; skin/model/resource correctness needs client verification |
| Vivecraft Spigot Extensions | No equivalent established | VR integration is outside the native NPC feature implementation |
| WorldEdit | Installed `worldedit-mod-7.3.8.jar` | Platform counterpart present; permissions and selection behavior not validated here |
| OpeNLogin directory | No enabled root jar | Historical data alone is not an active old plugin; authentication is not implemented by Citizens |
| bStats / PluginMetrics directories | Metrics data | Not NPC gameplay features |

The table groups the two Essentials jars separately and lists all active jars; ResourceHack's row uses its function as a label. `cam-server` is a command dependency from dialogues whose old provider has not been established from this plugin list; it must not be assumed to be Citizens or ProtocolLib merely because NPCs trigger it.

## Claude Code / CC Switch history cross-check

Read the project's native Claude session store at
`C:/Users/ben_l/.claude/projects/C--Users-ben-l-Documents-Coding-citizens2-neoforged/`:
15 top-level JSONL session files, the project memory directory, and `.claude/plans/citizens2-neoforge-port.md`. Provider credentials and unrelated projects were not needed. This checks the stored Claude conversations used for this project, not CC Switch's provider configuration database.

| History | Current verification / interpretation |
|---|---|
| `42207519` / `4a1a34c2`: initial port and acceptable platform changes | Interface/capability preservation is distinct from Bukkit binary compatibility. The current request requires accounting for all gameplay features |
| `634fe8e2`: guards, skins, conversations, groups | Most useful incident history. User confirmed skins and later LookClose working; focus was defined as narrower FOV/slower movement, not a new requirement to freeze all NPC navigation |
| `634fe8e2`: 2026-08-24 permission work handled elsewhere | Do not replace group storage inside Citizens. Audit the bridge and leave the separately maintained permission subsystem intact |
| `0ea32c7a`: player-NPC performance work | `ChunkMapMixin`, `EntityGetterMixin`, `ServerPlayerMixin`, player-info ordering, and chunk load/unload handling are present. No new full-modpack performance profile was collected in this audit |
| Memory: "122/122 NPC commands", "no known gaps" | Incomplete accounting: dedicated commands under versioned traits were omitted. The current broad inventory disproves full command parity |
| Memory: all holographic dialogues disabled | Current data has 41 enabled; use the current files as evidence |
| Memory: economy method-shape mismatch | Stale relative to current economy API source; transaction wiring and outcomes remain separate concerns |
| Memory: knockback assertions can fail when fixtures are absent | Consistent with both current isolated failures; do not delete failures or label them fixed without checking entity existence and chunk load timing |
| Memory: TLM migrated BlockPos / maid crashes | Historical independent migration issue, not automatically a Citizens regression. No production entity edits were performed |

Session transcripts remain in ignored local artifacts. Their narrative claims were treated as leads and checked against current evidence, not accepted as verification results.

## Verification performed and limits

| Check | Result |
|---|---|
| `gradlew test build` | Success, initially cached |
| `gradlew test --rerun-tasks` | **112 tests passed**, zero failures/errors/skips; all ten Gradle tasks executed |
| Opt-in Interactions acceptance probes | **4 executed, 3 failed**: both Bukkit note-block ID conversions and cooldown-record preservation. Namespaced sound pass is the control |
| Isolated fixture server, run 1 | **103 PASS / 3 FAIL**; failed `knockback-disabled-held-still`, `knockback-npc-survived`, `knockback-control-survived` |
| Isolated fixture server, run 2 with command lookups | **105 PASS / 1 FAIL**; failed `knockback-control-survived`; native `give`/`setblock` found, namespaced variants absent |
| Runtime environment | Existing test world/config copied to `artifacts/audit-server`; bound only to `127.0.0.1:25578`; original test fixture and production world unchanged; both audit servers shut down normally |
| Production read-only observations | NPC IDs/UUIDs, jar contents, conversation file hashes, aliases, installed mod inventory, public API bytecode, economy table counts, current log |
| Not established | Full modpack 21.1.248 acceptance; fresh real-client rendering; online shop/payment/quest delivery; all 146 dialogue paths; all upstream method/flag semantics; performance under the full population |

The current default tests do not cover the dialogue source set. The opt-in init script assigns the dialogue mod as the tested mod and isolates its tests, avoiding the split-package error caused by adding dialogue-package tests to the Citizens test module. Its failures are deliberately retained as open acceptance failures, not weakened to match the implementation.

## Reproduction and evidence files

Run from the repository root, supplying actual input directories:

```powershell
# Use a local environment with PyYAML installed; no server is started by this tool.
python tools/audit_npc_parity.py --plugins '<old-server>/plugins' --server '<new-server>' --upstream-ref e2998fd5fb8486b2551c1bcd366e37c263a6b35f --out artifacts/parity-audit

# From neoforge/, reproduce the three known acceptance failures:
.\gradlew.bat -I ../tools/audit-tests.gradle test --tests '*LegacyParityAuditTest' --console=plain

# Ordinary regression suite, with the normal test configuration:
.\gradlew.bat test --rerun-tasks --console=plain
```

The audit tool emits command/class/old-trait CSVs and structured inventories of every conversation's triggers, commands, placeholders, feature flags, referenced items, deployment hashes, and affected YAML paths. Run it against an immutable snapshot for strictly repeatable counts: the current server may save while being read.

Local raw evidence: `artifacts/parity-audit/`, `artifacts/audit-tests.log`, `artifacts/audit-acceptance.log`, `artifacts/audit-acceptance-results.xml`, `artifacts/audit-server-run.log`, `artifacts/audit-syntax-run.log`, and inspected Paradigm bytecode text. Private server data, logs, library dependencies, and transcripts are not committed or uploaded. The small public-source inventories are committed beside this report.

## Acceptance work still required

1. Restore dialogue command routing first: namespaced commands, correct Paradigm membership calls, and real economy-shop / quest / camera services. Validate the whole action and all reward definitions before consuming items or funds.
2. Restore progress predicates and conversation-scoped persistent cooldowns; implement real randomness, proximity start, movement/global restrictions, holographic dialogue, and all rendered option indices. Preserve the user's confirmed focus behavior and avoid hardcoded UI language.
3. Wire Citizens' native economy/permission mutation interfaces where required; complete relevant native trait commands and document version-only exclusions. Fill Sentinel's actually used omitted behavior, including healing.
4. Reconcile ItemEdit internal data and missing reward IDs with the owner's source data. Check 1.20.1 command NBT versus 1.21.1 components instead of assuming that stripping a command prefix is sufficient.
5. Stabilize the fixture's existence/loading assertions, rerun on the deployment NeoForge version, then test packaged jars in an isolated copy of the real modpack with a real client. Cover purchase success/failure, insufficient funds, missing rewards, reconnect/restart, quests, guards, mount/seat/sleep, skin visibility, teleport/respawn, and unloaded chunks.

Until these gates pass, the defensible statement is **NPC identity migration is intact, but NPC gameplay feature coverage is incomplete**. No zero-error or one-to-one behavior guarantee is justified.

Follow-up repair (2026-09-11 01:20:59 +08:00): Interactions now reads the legacy global chat and mob-target flags on startup/reload. Three configuration tests and four controller/event probes pass (ordinary chat denied/allowed and actual Mob.setTarget denied/allowed). Runtime totals are 107 NPC assertions and 65 probes on NeoForge 21.1.248. Nearby-target clearing is implemented but lacks a dedicated spatial runtime assertion; command/inventory restrictions and full modpack parity remain open. This does not change the incomplete coverage verdict.

Follow-up repair (2026-09-11 01:26:32 +08:00): Restored dialogue command blocking and legacy whitelist-prefix behavior. Player connection commands are intercepted after vanilla validation, while internal player-source reward commands still run. The current option-click command remains allowed. Ten new runtime probes pass; totals are 107 NPC assertions and 75 runtime probes, plus 21 dialogue audit tests. The signed execution branch is exercised with no signable arguments; real connected-client signature chains and option UI acceptance remain open. Inventory restrictions and legacy skip-dialogue controls are still incomplete. Full parity verdict remains incomplete.

Follow-up repair (2026-09-11 01:34:38 +08:00): Restored %next% buttons, custom nextDialogueText/nextDialogueHover, interactions skipdialogue, and opt-in skip_dialogue_on_npc_click. Completion runs last_actions once on the session tick; duplicate requests and option-stage skips are rejected. Fourteen new runtime checks pass, including actual command dispatch, reward counts, and NPC click-controller paths. Totals: 107 NPC assertions and 89 runtime probes on NeoForge 21.1.248. The inspected old files do not currently use %next%, and NPC click skipping remains disabled by their configuration. Connected-client UI, animated text, inventory restrictions and complete modpack parity remain open.
