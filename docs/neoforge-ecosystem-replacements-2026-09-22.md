# Citizens NeoForge scope and ecosystem replacements

Reviewed: 2026-09-22 19:43:17 +08:00 (UTC+08:00).
Target: Minecraft 1.21.1. The repository declares NeoForge 21.1.233; the existing server/runtime-audit record uses 21.1.248.

## User-directed scope

The user clarified that the objective is to port Citizens and integrate it with the existing NeoForge ecosystem. Porting every former Bukkit plugin is outside that objective.

The existing self-developed **Yuuniverse Interactions** is already part of that ecosystem and remains the dialogue provider. The earlier recommendation to replace it with RPG Dialogue is withdrawn. Evaluate new providers for capabilities that the retained stack does not already supply.

The repository builds its dialogue source set as a separate `yuuniverse-interactions` jar with mod ID `interactions` and a Citizens dependency. Read-only server inventory also confirms `E:/yunniverse-server/mods/[自研]yuuniverse-interactions.jar`. The local implementation already handles all eighteen original action verbs, dialogue conditions, persistence and presentation; the saved-item follow-up records 99 dialogue unit tests and dedicated runtime suites. Those results describe the recorded local implementation, not a new validation of the deployed jar.

Citizens owns its NPCs, navigation, traits, commands, persistence and useful native extension contracts. Other mods should own dialogue presentation, quest state, permission/group management, economic ledgers, system shops, camera paths and specialist combat. This project should supply small, optional adapters and necessary data migration at those boundaries.

The earlier reports' complete Citizens/Interactions/Sentinel stack criterion is superseded by this clarification. Missing full Quests, GroupManager, EssentialsX, ItemEdit or PlaceholderAPI functionality is not, by itself, a Citizens implementation defect. Missing reference class names also do not establish missing native behavior or require a Bukkit binary-compatibility runtime.

Existing migration code does not oblige the project to keep expanding those plugins' feature sets. Provider replacement and removal of existing code require a separate implementation and validation step; this research does not remove functionality or install mods.

## Recommended providers

The versions below are verified candidate artifacts, not a claim that a combined server has passed acceptance.

| Responsibility / old provider | NeoForge choice | Evidence and integration boundary |
|---|---|---|
| Permissions and general administration: GroupManager / EssentialsX | Keep **Paradigm Essentials 2.4.2b**, already installed | Its native permission/group and administration services are already connected in this repository. The published 1.21.1 successor is **2.5.0b**, a beta; an upgrade is a separate decision. Map required workflows to Paradigm's capabilities instead of rebuilding GroupManager's data model inside Citizens. |
| Economy, currencies, system shops and financial rewards | Keep **Yuuniverse Economy** | The server inventory contains 0.4.1-SNAPSHOT. Existing bridge work also documents the local 0.4.2-SNAPSHOT shop/account API. Economy remains the owner of balances, stock and settlement; Citizens calls the supported API. |
| Dialogue: Interactions | Keep **Yuuniverse Interactions**, already developed and installed | It is an independent native NeoForge mod already integrated with Citizens and existing conversation files. Extend its external service adapters where needed, especially quest conditions/actions. Its existing capabilities and validation are not grounds for introducing another dialogue engine. |
| Quests: Bukkit Quests | **FTB Quests 2101.1.36** | A released native quest engine with item/custom tasks, rewards and authoritative quest/team state. NPC interaction/delivery needs an adapter to FTB's actual task/progress model; it is not a Bukkit Quests YAML importer or an existing Citizens integration. |
| Item editing and shared saved items: ItemEdit | **RSItemEditor 1.0.1** | A server-only 1.21.1 mod providing `/itemedit` (`/ie`), `/itemstorage` (`/is`) and `/serveritem` (`/si`). Supports native components, per-command permission nodes and shared saved items. Old YAML definitions need migration to its NBT-backed library. It does not replace Citizens' own safe item persistence. |
| Pack-specific event glue | Optional **KubeJS 2101.7.2-build.377** and **FTB XMod Compat 21.1.12** | The version-specific FTB integration exposes `FTBQuestsEvents.customTask`, `customReward`, `started` and `completed`. Useful for pack-owned interactions; a small Java adapter is also possible. Installing KubeJS does not automatically expose all Citizens-specific events or preserve old script syntax. |
| NPC variables and external text values: selected PlaceholderAPI expansions | **Paradigm's placeholder registration API** and the chosen provider's native value/condition API | The installed Paradigm jar already contains `PlaceholderService` and `ExternalPlaceholderResolver`. Register owned NPC values explicitly. This is not a drop-in runtime for all Bukkit PlaceholderAPI expansion jars. FTB quest values should come from FTB; economy values should come from Economy. |
| Camera paths | **CMDCam 2.2.9** plus **CreativeCore** | Existing bridge work already validates the provider and original scenes. CreativeCore is installed; CMDCam was not present in the inspected production mod filenames. Server-triggered camera paths need the relevant client and server setup. |
| Dedicated guards: Sentinel-style gameplay | **Guard Villagers 2.4.12**, if separate guard entities meet the gameplay need | Provides armed guards, patrol/follow behavior and native combat. It owns its guard entities. No ready-made, verified adapter attaching its AI to Citizens NPCs was found. Full Sentinel administration/combat must not remain an implicit Citizens completion gate. |

Retained baseline: **Citizens + Yuuniverse Interactions + Paradigm + Yuuniverse Economy**, with the existing CMDCam integration for camera scenes. Evaluate FTB Quests for the missing quest service and RSItemEditor if native item-authoring/shared-library tools are needed. KubeJS is optional integration tooling. Guard replacement is a separate gameplay choice.

## Version and deployment details

- Retaining Yuuniverse Interactions does not introduce the researched RPG Dialogue dependency, its separate dialogue format or its NeoForge version floor.
- The FTB Quests candidate requires client and server installation. Provider version availability does not establish rendering, input, multiplayer or interaction compatibility with Citizens and Yuuniverse Interactions.
- FTB Quests 2101.1.36's published jar requires Architectury **13.0.8+**, FTB Library **2101.1.36+** and FTB Teams **2101.1.9+**. Its optional FTB XMod Compat floor is **21.1.7**; the checked 21.1.12 artifact satisfies it.
- FTB XMod Compat 21.1.12 is a verified **1.21.1** artifact. Its current default Git branch targets newer Minecraft, so implementation should reference its 1.21.1 branch or the chosen artifact instead of copying current-main APIs.
- RSItemEditor 1.0.1's jar requires **Kotlin for Forge 5.3.0+**. It uses world-scoped NBT files for shared items and per-player storage. The existing ItemEdit database is not already in that format, and unavailable provider items still need their real mod definitions.
- KubeJS's checked release declares Rhino and Better Advanced Tooltips as required dependencies. Its client requirement depends on the content/scripts in use; the quest/dialogue clients already need their own providers.
- Paradigm's native group semantics should guide the adapter. A legacy primary-group command does not justify destroying unrelated memberships or implementing an independent shadow permission system.
- Preserve the conversation format already supported by Yuuniverse Interactions. Migrate external service IDs and the needed quest/item operations when adopting a new service, with an explicit report of unsupported data. Keep payments, quest completion and item delivery under their owning services.

## Other candidates reviewed

| Candidate | Verified 1.21.1 NeoForge release | Disposition |
|---|---|---|
| RPG Dialogue | **1.0.3** | Researched comparison only; replacing the existing Yuuniverse Interactions is not proposed. Its documented `DialogueManager.open(player, id, speaker)` and custom action/condition APIs exist in the published jar. It requires both sides and NeoForge **21.1.244+**; these requirements apply only if a future explicit decision selects it. |
| Aviel's Dialogue Mischiefs | **0.8.5** | Open-source alternative with branching dialogue, player flags, a node editor and `AdmDialogueApi.openDialogue(ServerPlayer, Entity, String)`. Its source also registers a broad `/npc` command tree overlapping Citizens' commands. Command ownership needs resolution before treating it as a clean companion. Current source and a published version are available, but a Citizens runtime pairing has not been tested. |
| VNDialog | **1.0.6-1.21.1** | Candidate for visual-novel-style portraits and branching dialogue. Less direct integration evidence was established than for RPG Dialogue; not the primary recommendation. |
| Easy NPC: Core | **7.12.1** | An additional NPC system with dialogue and configuration modules. It is not established as a drop-in dialogue or combat extension for existing Citizens entities. |
| CustomNPCs-Unofficial | **NeoForge-1.21.1.20241226** | An alternative NPC system with its own AI and scripting. Choosing it as the main NPC engine would change the user's Citizens-port objective; not the default recommendation. |
| LuckPerms | Modrinth's exact 1.21.1/NeoForge filter lists **5.4.139** and **5.4.140** | Not a reason to replace the already installed Paradigm. Old login failures are documented, but issue #4235 has since closed with later update discussion. Neither universal incompatibility nor a safe current replacement was established here; evaluate a specific artifact separately if needed. |

## Concrete integration work still needed

1. Keep the Citizens core audit scoped to native NPC behavior, data integrity and the extension contracts the integrations actually consume.
2. Reuse the existing Citizens-to-Yuuniverse Interactions connection, conversation data and lifecycle coverage. Add only the external service boundary needed by a selected provider, rather than creating another dialogue adapter/engine.
3. If FTB Quests is selected, prove one NPC delivery task and an existing Interactions condition/action use authoritative FTB progress, with explicit player/team semantics, inventory consumption, replay handling and restart persistence. Use the provider's actual APIs rather than assuming command execution equals completed work.
4. Route permission, economy, item and camera operations through existing services. Migrate the required old content/data; do not implement whole replacement plugin suites in this repository.
5. Validate the retained stack and any selected external integrations with real clients and the target modpack. Dialogue-engine replacement is not part of this plan.

These are integration proposals. No mod was installed, no server was restarted, and no production data or existing implementation was changed by this research.

## Verification and primary sources

Read-only inspection covered the current server's provider filenames, the installed Paradigm API class inventory, published version metadata, official documentation and selected source APIs. FTB Quests, FTB XMod Compat, RPG Dialogue and RSItemEditor release jars were read in memory to verify loader/dependency metadata and relevant class presence; the jars were not executed. No new runtime acceptance is claimed.

- Existing Yuuniverse Interactions: [separate build and jar](../neoforge/build.gradle), [native mod descriptor and Citizens dependency](../neoforge/src/dialogue/resources/META-INF/neoforge.mods.toml), [recorded dialogue/provider validation](saved-item-payload-parity-2026-09-22.md).
- [RPG Dialogue 1.0.3 release](https://modrinth.com/mod/rpg-dialogue/version/7PDH51XZ), [developer API](https://wiki.pixeldreamstudios.net/mods/rpg-dialogue/for-developers), [speaker bindings and commands](https://wiki.pixeldreamstudios.net/mods/rpg-dialogue/speakers).
- [FTB Quests source, 1.21.1](https://github.com/FTBTeam/FTB-Quests/tree/1.21.1/main), [official published versions](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-quests-neoforge/maven-metadata.xml), [2101.1.36 dependency POM](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-quests-neoforge/2101.1.36/ftb-quests-neoforge-2101.1.36.pom).
- [FTB XMod Compat 1.21.1 integration events](https://github.com/FTBTeam/FTB-XMod-Compat/blob/51c6f83b55993c085ab709199a0d20e949f4233f/neoforge/src/main/java/dev/ftb/mods/ftbxmodcompat/neoforge/ftbquests/kubejs/FTBQuestsKubeJSEvents.java), [official published versions](https://maven.ftb.dev/releases/dev/ftb/mods/ftb-xmod-compat-neoforge/maven-metadata.xml).
- [RSItemEditor 1.0.1 release](https://modrinth.com/mod/rsitemeditor/version/KEpGvGgz), [source and commands](https://github.com/resonaresmp/RSItemEditor).
- [Paradigm Essentials](https://modrinth.com/mod/paradigm), [placeholder API](https://github.com/Avalanche7CZ/Paradigm/blob/e2e90e12a721c498064e44f8ec3c563b225b321a/common/src/main/java/eu/avalanche7/paradigm/api/PlaceholderService.java).
- [KubeJS checked release](https://modrinth.com/mod/kubejs/version/THIGFPwf).
- [CMDCam 2.2.9](https://modrinth.com/mod/cmdcam/version/ZibX3UR8), [existing local service ownership and validation](native-service-bridges-2026-09-13.md).
- [Guard Villagers 2.4.12](https://modrinth.com/mod/guard-villagers/version/JyXugpy2).
- [Aviel's Dialogue Mischiefs 0.8.5](https://modrinth.com/mod/aviel-dialogue-mod/version/38418AUw), [public dialogue API](https://github.com/AV1el/Aviel-Dialogue-Mischiefs/blob/3324c965824f3411f33bf52886c5ccc2e8c55f68/src/main/java/net/aviel/dialogue/api/AdmDialogueApi.java), [overlapping command registration](https://github.com/AV1el/Aviel-Dialogue-Mischiefs/blob/3324c965824f3411f33bf52886c5ccc2e8c55f68/src/main/java/net/aviel/dialogue/command/DialogueNpcCommand.java).
- [VNDialog 1.0.6](https://modrinth.com/mod/nvdialog/version/KLaBhrVr), [Easy NPC Core 7.12.1](https://modrinth.com/mod/easy-npc-core/version/e5wGcNT7), [CustomNPCs-Unofficial 1.21.1](https://modrinth.com/mod/customnpcs-unofficial/version/5gdSEvLv).
- [LuckPerms version inventory](https://modrinth.com/plugin/luckperms/versions), [issue #4235 and follow-up discussion](https://github.com/LuckPerms/LuckPerms/issues/4235).
