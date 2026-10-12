# Citizens NeoForge progress, next work and plugin alternatives

Reviewed: 2026-10-12 11:05 +08:00 (UTC+08:00).
Target: Minecraft 1.21.1; repository baseline NeoForge 21.1.233, existing acceptance target 21.1.248.
Implementation baseline: `cb63529` on `neoforge-port`, plus the pre-existing, uncommitted delayed-command changes described below.

Keep **Citizens + Yuuniverse Interactions + Paradigm + Yuuniverse Economy**, with **CMDCam** for camera scenes. Finish and validate delayed-command retirement first. FTB Quests is the recommended next quest-provider candidate; RSItemEditor is an optional item-authoring replacement. Completing every former Bukkit plugin is outside Citizens core completion.

## Recovered context and current progress

The earlier chat **现在的neoforge移植对比原citizens功能还差哪些，既然具备gui是否客户端也要装** (`01a12df0-7205-7210-92d6-5d6c6df6dd1c`) supplied the recent parity review. The interrupted chats titled **/goal 继续补全citizens模组的功能** (`01a1257a-50fb-7463-a9b3-8fd37078ba02` and `01a12def-a497-7622-bfca-dd103e8eab87`) identify the pending command-lifecycle batch. The [September ecosystem decision](neoforge-ecosystem-replacements-2026-09-22.md) and [independent JAR record](independent-interactions-jar-2026-10-04.md) retain the established provider boundaries.

| Area | Finding | Evidence limit |
|---|---|---|
| Core NPC systems | Creation/removal, skins, equipment, navigation, waypoints, following, mounts, holograms, shops, commands, templates and behavior trees have implementations and substantial recorded acceptance. | Focused checks do not establish every provider/client combination. |
| Command coverage | A fresh declaration scan finds 192 upstream alias/modifier pairs and 194 native pairs. Only six upstream pairs remain unmatched: `npc chicken`, `cow`, `pig`, `coppergolem`, `mannequin`, `sulfurcube`. The historical 37 missing 1.21.1 command names and HPA aliases have entry points. | Counts are sets with different members, not a completion percentage. Those six commands concern newer-version entity capabilities; chicken/cow/pig NPC creation itself is supported. Options and runtime behavior require separate checks. |
| Latest committed gameplay | `cb63529` records command usage/cooldowns/sequence position after successful per-command payment. | Retained evidence reports 725 tests passing and 586 native checks on each of 21.1.233 and 21.1.248. These tests were not rerun for this planning task. |
| Interrupted draft | Modified `CommandTrait.java`, `Util.java` and `CreationRuntimeAudit.java` add ownership checks before delayed payment and final dispatch, plus refunds when payment callbacks retire an owner. | The baseline fails `delayed_command_retirement_remove_command_false`; the draft's retained 21.1.233 log completes 704 checks. No matching 21.1.248 retirement log, final ordinary release build, documentation update or commit was found. |
| Mod separation | Citizens and Interactions build as separate JARs; dependency is Interactions → Citizens. | Dialogue, conversation progress, conditions and cutscene orchestration stay in Interactions. Citizens supplies native NPC systems/APIs. |
| GUI/client | Citizens uses vanilla container/anvil/trade presentation. Its configuration GUI currently exposes rename, as does the checked-in upstream implementation. | These GUIs do not themselves require a Citizens client mod. Joining without Citizens still needs physical-client acceptance; content/camera/quest mods have independent requirements. |
| Sentinel compatibility | `SentinelTrait` provides native melee guards and reads legacy fields. | Ranged combat/full Sentinel parity is unfinished and is not an implicit Citizens-core completion requirement. |

The declaration scan balances annotation parentheses and handles both scalar and array arguments. It compares the checked-in `main` and `neoforge` trees, not a newly fetched upstream checkout.

### Source, release artifacts and installed artifacts

Read-only inspection of `E:/yunniverse-server/mods` establishes file presence, not the live process's loaded versions:

- Paradigm's installed descriptor/manifest report **2.5.0**. The exact 1.21.1/NeoForge release query now returns **2.5.2**, an available candidate rather than a tested upgrade recommendation.
- **CMDCam 2.2.9** and **CreativeCore 2.13.50** are present. September statements that CMDCam was absent are outdated inventory observations.
- Yuuniverse Economy's installed descriptor reports **0.4.1-SNAPSHOT**. The neighboring project's current README documents a **0.5.0 integration surface**. Do not assume the installed JAR has that newer API.
- FTB Quests, RSItemEditor, KubeJS and Guard Villagers were not found in the inspected mod filenames.
- The local Citizens/Interactions release hashes match the October 10 payment-usage validation record. Both installed JARs differ. The local releases therefore do not include the subsequent uncommitted retirement draft.

| Artifact | Local release SHA-256 | Installed SHA-256 |
|---|---|---|
| Citizens | `5C448306B79A8A309ABBAD09E516AEBCFB90CCC70468A8171B067BAEED986BD7` | `73E82636CC6DA1CDF534A6666B43D8553EE7458EB38B18BD685C45E3DD8A6673` |
| Interactions | `7EB8095144562AA81B3293316C494240A378A0E813AD4ACAB0904605ECF7B866` | `3BC4BDFF01F94473395E6E52D3DD5CA3C31E3D06A55C38623187D8712A12E113` |

Different hashes prove different artifacts; they do not identify every deployed feature or establish which files a running server loaded.

## Ordered implementation plan

Each row is a bounded batch with an observable completion condition. Document and commit each coherent change with a timestamped root walkthrough entry.

| Order | Work | Completion condition |
|---|---|---|
| 1 | Recover the delayed-command draft. Review command identity, trait/registry ownership, despawn/respawn, deletion/replacement with the same ID, and both scheduler boundaries. Review removal of public `NPCCommand.run(NPC, ServerPlayer)` for source compatibility. | Ordinary and packet NPCs cannot execute retired work; unrelated edits/fresh clicks remain valid. Payment-callback retirement refunds the relevant receipt without recording usage. Validate final sources on 21.1.233 and 21.1.248, restore the ordinary build, verify separate JARs/no audit probes, document and commit. |
| 2 | Close command transaction semantics: global click costs, dispatch-event recursion, scheduling/dispatch failures after payment, temporary permission lifetime. | Define each charge/refund boundary. Rejected/no-op attempts do not consume the wrong history or duplicate effects. Exercise actual dispatch/provider failures; arbitrary external command effects cannot be assumed rollback-safe. |
| 3 | Finish coordinated configuration/shop/NPC reload failure handling, building on existing participant staging and atomic writes. | Failure at each publication/retirement boundary leaves a defined usable state, without stale menus/listeners, lost shops or mismatched registry ownership. Include restart and provider absence/reappearance. |
| 4 | Reconcile the retained provider versions and content references. Pin the tested Paradigm, Economy, Interactions, CMDCam and CreativeCore artifacts. | Test actual APIs for group mutations, placeholder ownership, currency precision, known offline UUIDs and shop preflight. Recheck unresolved shop/account/group mappings against current data; never infer identities from similar labels. |
| 5 | Prove one FTB Quests workflow if adopted, through the existing Interactions/native service boundary. | An NPC delivery advances authoritative player/team state once, consumes the intended items, handles repeated clicks/full inventory/reconnect, and survives restart. Dialogue conditions/actions read that same state. Explicitly migrate old IDs/content; FTB Quests does not import Bukkit Quests YAML automatically. |
| 6 | Complete required item/content migration; adopt RSItemEditor if its authoring/shared-library tools are needed. | Report conversion of actual legacy components, profiles, attributes, containers and plugin data. Preserve unsupported records/missing items. Historical undefined item IDs 6/15/144 and `eyemod:eyephone` need source/provider resolution, not fabricated replacements. |
| 7 | Run physical-client acceptance with the target modpack; prepare a release/deployment decision afterward. | Verify joining without Citizens, skins/texture services, holograms, menu input/item prediction, mounted dialogue, dimension changes, camera playback, provider lifecycle and actual `send_to_server` arrival through proxy/backends. Record exact server/client artifacts and candidate hashes. |

Steps 1–3 address concrete Citizens correctness gaps. Steps 4–7 establish the usable migrated stack. Full guard combat, another NPC engine, general administration suites and arbitrary Bukkit expansions remain separate scope. Prioritize reproduced defects, supported public contracts and the server's actual content rather than indefinitely adding hypothetical provider combinations.

## Existing mods corresponding to the old plugins

The legacy directory contains **21 plugin JARs**. All are covered below; EssentialsX/EssentialsXSpawn and ProtocolLib/PacketEvents share rows. Candidate releases were checked for **Minecraft 1.21.1 + NeoForge** unless explicitly stated otherwise. Similar purpose does not imply compatible save formats, APIs or commands.

“Server” means the candidate's published metadata describes server use without that mod on ordinary clients; separate content mods can still be required. “Both” means client and server installation.

| Former Bukkit plugin(s) | Existing mod or retained owner | Match and remaining boundary | Client side |
|---|---|---|---|
| Citizens | **This port**; alternatives [Easy NPC: Core 7.14.0](https://modrinth.com/mod/easy-npc-core/version/wpGE79aG), [CustomNPCs-Unofficial 1.21.1.20241226](https://modrinth.com/mod/customnpcs-unofficial/version/5gdSEvLv) | Keep this port. Alternatives own their NPCs; they are not verified Citizens trait/API add-ons or save importers. | Port uses vanilla presentation; alternatives both. |
| Interactions | **Yuuniverse Interactions**, separate local JAR | Keep the existing conversation format and integration. Add selected external service adapters in its own module. | Current vanilla presentation; camera/content dependencies separate. |
| GroupManager | **Paradigm**, installed 2.5.0; [LuckPerms 5.4.140 NeoForge](https://modrinth.com/plugin/luckperms/version/rnvg05YO) is an alternative | Keep Paradigm's authority. Migrate membership/inheritance deliberately; no automatic GroupManager YAML compatibility is established. | Server. |
| EssentialsX + EssentialsXSpawn | **[Paradigm Essentials](https://modrinth.com/mod/paradigm)** | Existing administration, teleport/spawn and related service owner. Map required options/data; do not assume every EssentialsX feature. Financial operations belong to Economy. | Server. |
| GemsEconomy | **Yuuniverse Economy**; [Impactor 5.3.5](https://modrinth.com/mod/impactor/version/VaF6hkni) is an alternative API/service | Retain configured currencies, UUID accounts, precision and ledger/import paths. Add an Impactor service adapter only for a real consumer. | Server. |
| EconomyShopGUI Premium | **Yuuniverse Economy system shops**; [SDM Shop 3.2.0](https://modrinth.com/mod/sdm-shop/version/HyVWYXLr) is an alternative | Local owner covers catalog/UI/stock/settlement. SDM offers configurable shops/quest integration with its own economy/content; it is not a Premium-data importer. | Local vanilla menus; SDM both. |
| QuickShop | **Yuuniverse Economy coordinate shops**; [Lightman's Currency 1.21-2.3.0.6](https://modrinth.com/mod/lightmans-currency/version/XMHRtEIk) or [Spud's Shop Fork 1.9.9](https://modrinth.com/mod/spuds-shop-fork/version/ilCCpqQi) | Alternatives offer protected shops/traders; Lightman's also has coins/accounts/admin traders. Neither preserves this server's ledger, database or settlement rules automatically. | Local vanilla menus; alternatives both. |
| TimeIsMoney | **Yuuniverse Economy payout service** | Already owned/implemented in the neighboring project. Verify migrated activity/timestamp/AFK/reward rules against the selected release. | Server. |
| Vault | **Yuuniverse Economy native monetary facades + Paradigm/native permission services**; optional **Impactor** | Map economy and permission/chat responsibilities to their owners. Native facades do not load Vault-dependent Bukkit binaries. Impactor supports a delegating economy service. | Server. |
| Quests | **[FTB Quests 2101.1.36](https://www.curseforge.com/minecraft/mc-mods/ftb-quests)** | Recommended quest candidate. NPC hooks, dialogue conditions, player/team semantics and old data migration still need work. | Both. |
| ItemEdit | **[RSItemEditor 1.0.1](https://modrinth.com/mod/rsitemeditor/version/KEpGvGgz)** | Close command match: `/ie`, `/is`, `/si`, components and shared/personal items. Convert old YAML to world-scoped NBT; verify permissions with Paradigm. | Server; Kotlin for Forge required. |
| PlaceholderAPI | **Paradigm placeholder registration + providers' native value APIs** | Citizens already registers owned NPC values. Arbitrary Bukkit expansions and inbound evaluation of all external tokens are not automatic. | Server. |
| ProtocolLib + PacketEvents | **Minecraft/NeoForge networking and focused native hooks** | Port the packet behavior actual consumers need. Custom-payload registration is not general interception; use native events/scoped hooks where interception is necessary. | Protocol-dependent; new custom messages may need clients. |
| Sentinel | Existing melee compatibility; **[Guard Villagers 2.4.12](https://modrinth.com/mod/guard-villagers/version/JyXugpy2)** for separate guards | Own armed guards and patrol/follow gameplay. No verified adapter attaches its AI to Citizens NPCs. Full Sentinel ranged/combat/admin parity remains separate. | Guard Villagers both. |
| CoreProtect CE | **[NeoProtect 1.0.4](https://modrinth.com/mod/neoprotect/version/TK4G6K3x)**; **[GriefLogger 1.2.10](https://modrinth.com/mod/grieflogger/version/IrRNGJIR)** | NeoProtect documents inspection, block/container logging and rollback previews. GriefLogger documents logging/lookup; rollback parity was not established. Neither was tested against Create/MTR or the old database. | Server. |
| WorldEdit Bukkit | **[WorldEdit mod 7.3.8](https://modrinth.com/plugin/worldedit/version/WTAFvuRx)**, present | Native distribution of the same building tool family. Validate needed schematics/modded-block workflows. | Server operation; client tooling per workflow. |
| CurveBuilding | **[CurveBuilding](https://github.com/kous500/CurveBuilding)** exists for Bukkit/Fabric; WorldEdit provides related building tools | Old descriptor uses WorldEdit and Bézier placement. Official source advertises Bukkit/Fabric; no native 1.21.1 NeoForge release verified. WorldEdit alone does not establish identical curve/pattern placement; Connector pairing is untested. | Unresolved for the native target. |
| ResourceHack | **No verified equivalent selected** | Its descriptor identifies `arcnode.reshack.ResourceHack` and a PacketEvents dependency, without explaining packet behavior. Inspect implementation before choosing vanilla pack delivery or a pack-management mod. | Unresolved. |
| Vivecraft Spigot Extensions | **[Vivecraft 1.21.1-1.3.15 NeoForge](https://modrinth.com/mod/vivecraft/version/LjqlbRAO)** | Native VR/server-support candidate. Check the old extension's specific rules and modpack compatibility. | VR players need clients; server component depends on features. |

Optional event/script glue: **[KubeJS 2101.7.2-build.377](https://modrinth.com/mod/kubejs/version/THIGFPwf)** with **FTB XMod Compat 21.1.12**. These do not translate Denizen/Bukkit scripts or automatically expose Citizens events. Denizen was not in the inspected inventory.

Camera scenes retain **[CMDCam 2.2.9](https://modrinth.com/mod/cmdcam/version/ZibX3UR8)** and CreativeCore, already present in the target directory. Participating clients need camera support.

### Dependencies and selection limits

- FTB Quests 2101.1.36's POM names Architectury 13.0.8, FTB Library 2101.1.36 and FTB Teams 2101.1.9; the earlier release-JAR inspection records these as minimums. Maven also lists Library 2101.1.37 and Teams 2101.1.11. Test an explicit compatible set. Existing Architectury 13.0.11 does not supply the missing FTB dependencies.
- FTB XMod Compat 21.1.12 is the 1.21.1 candidate. Checked source exposes `FTBQuestsEvents.customTask`, `customReward`, `started` and `completed`.
- RSItemEditor requires Kotlin for Forge; the earlier JAR audit records 5.3.0+. KubeJS lists Rhino and Better Advanced Tooltips as required dependencies; side requirements depend on scripts/content.
- GriefLogger lists Architectury API and SuperMartijn642's Config Lib. NeoProtect lists no additional dependency mods. Logging/rollback still needs modded-block/container acceptance.
- Impactor's project prose includes outdated platform/version descriptions, while the exact release inventory includes NeoForge 1.21.1. Use the selected artifact/API rather than an assumed default branch.
- Ledger's exact 1.21.1/NeoForge query returned no versions; availability on another loader does not establish native availability here.
- Release availability and advertised features are research evidence. No new mod was installed, upgraded or executed by this review.

## Native API direction

| Bukkit-facing concept | Native direction |
|---|---|
| CitizensAPI consumers | Adapt/compile against this port's NPC/trait/navigation/event contracts and Minecraft types. Similar packages do not establish Bukkit binary compatibility. |
| Events, commands, scheduler | NeoForge event buses, Brigadier and existing Citizens scheduling/lifecycle APIs, preserving main-thread and ownership rules. |
| Vault/GemsEconomy | Economy-owned account/currency/transaction APIs and native facades. Keep balances, idempotency and payouts with Economy. |
| PlaceholderAPI | Register owned values with Paradigm; obtain quest/economy values from their services. Inbound evaluation is separate from outbound registration. |
| Quest tasks/rewards | Actual FTB task/team state APIs or version-specific XMod/KubeJS events. A dispatched command does not prove task completion. |
| ItemMeta and serialization | Native item components and registry-aware persistence, with explicit conversion and unavailable-data retention. |
| ProtocolLib/PacketEvents | Native packets/events and scoped interception; NeoForge custom payloads for actual custom messages. |

## Sources and verification

Local evidence: [remaining checklist](remaining-citizens-work-2026-09-26.md), [payment acceptance](command-payment-usage-2026-10-10.md), [service bridge history](native-service-bridges-2026-09-13.md), [Sentinel](../neoforge/src/main/java/net/citizensnpcs/trait/SentinelTrait.java), [GUI menu types](../neoforge/src/main/java/net/citizensnpcs/api/gui/InventoryType.java), [separate build source sets](../neoforge/build.gradle), and retained `artifacts/command-retirement-before.log`, `artifacts/command-retirement-native-233.log`, `artifacts/command-payment-usage-validation.json`. Runtime artifacts are local evidence and may be ignored by Git.

Additional primary sources fetched during this review:

- [Paradigm 2.5.2](https://modrinth.com/mod/paradigm/version/24uoAJR4).
- [FTB Quests 1.21.1 source](https://github.com/FTBTeam/FTB-Quests/tree/1.21.1/main), [release metadata](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-quests-neoforge/maven-metadata.xml), [2101.1.36 POM](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-quests-neoforge/2101.1.36/ftb-quests-neoforge-2101.1.36.pom).
- [FTB XMod Compat metadata](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-xmod-compat-neoforge/maven-metadata.xml), [checked event source](https://github.com/FTBTeam/FTB-XMod-Compat/blob/51c6f83b55993c085ab709199a0d20e949f4233f/neoforge/src/main/java/dev/ftb/mods/ftbxmodcompat/neoforge/ftbquests/kubejs/FTBQuestsKubeJSEvents.java).
- [RSItemEditor commands/dependencies/storage](https://github.com/resonaresmp/RSItemEditor).
- [CurveBuilding supported loaders](https://github.com/kous500/CurveBuilding), [release inventory](https://github.com/kous500/CurveBuilding/releases).
- [NeoForge 1.21.1 events](https://docs.neoforged.net/docs/1.21.1/concepts/events/), [custom payloads](https://docs.neoforged.net/docs/1.21.1/networking/payload/).

Modrinth descriptions, side metadata and exact-filter release endpoints were fetched for the table's candidates. The query was `/v2/project/<slug>/version?loaders=["neoforge"]&game_versions=["1.21.1"]`; release links pin the reviewed artifacts. The installed directories and neighboring Economy README were inspected read-only. This task changes documentation only; it does not certify the pending gameplay draft, rerun gameplay tests or deploy artifacts.
