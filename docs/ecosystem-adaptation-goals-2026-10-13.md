# Adopted NeoForge integrations and porting goals

Decision: 2026-10-13, UTC+08:00. Target: Minecraft 1.21.1, Java 21, deployed NeoForge 21.1.248; Citizens' existing 21.1.233 baseline remains separate.

The user selected **Yuuniverse Economy GUI to replace EconomyShopGUI Premium and QuickShop**, and selected **FTB Quests, RSItemEditor, Paradigm's placeholder API, Guard Villagers, NeoProtect, GriefLogger, Vivecraft NeoForge, KubeJS and FTB XMod Compat** for adaptation. These are now the chosen integration targets, superseding the optional-candidate wording in the [October 12 review](citizens-next-work-2026-10-12.md) and [September ecosystem report](neoforge-ecosystem-replacements-2026-09-22.md).

Citizens retains NPC behavior and its existing native extension contracts. Yuuniverse Interactions remains a separate dialogue mod. Economy owns money, system/player shops, stock, settlement and its GUI; Paradigm owns permissions and placeholder registration; FTB owns quest/team progress. Installing a provider establishes neither an adapter nor automatic import of Bukkit data.

## Selected artifacts and sides

| Provider | Selected version | Deployment role |
|---|---|---|
| Yuuniverse Economy | Active 0.4.1-SNAPSHOT; validated 0.5.0-SNAPSHOT prepared from `072703c`, tooling `9d501be` | Server economy/GUI owner. The user-authorized update is staged for the next planned restart; see the preparation record below. |
| Paradigm | Existing 2.5.0 retained | Server. Its JAR already supplies `PlaceholderService.register(namespace, key, resolver)`, `ExternalPlaceholderResolver` and registration handles. There is no additional PlaceholderAPI JAR to install. |
| FTB Quests | 2101.1.36 | Both sides. Quest definitions/progress/rewards; adapt legacy quest IDs/content explicitly. |
| FTB Library / FTB Teams | 2101.1.37 / 2101.1.11 | Both sides; required FTB dependencies. |
| FTB XMod Compat | 21.1.12 | Both sides in this pack; connects FTB and KubeJS. |
| RSItemEditor | 1.0.1 | Server; requires Kotlin for Forge 5.12.0, which satisfies the JAR's 5.3.0 minimum. |
| Guard Villagers | 2.4.12 | Both sides. Separate guard entities; not an automatic Citizens/Sentinel AI replacement. |
| NeoProtect | 1.0.4 | Server; requires NeoForge 21.1.228+. |
| GriefLogger | 1.2.10-1.21.1-yuuniverse.1 (local source build) | Server; requires Architectury and SuperMartijn642's Config Lib 1.1.8. SQLite packaging repair validated below. |
| Vivecraft | 1.21.1-1.3.15 NeoForge | Server support and client pack. VR is a client choice; ordinary players need not enable VR. |
| KubeJS / Rhino | 2101.7.2-build.377 / 2101.2.7-build.85 | Both sides for the selected quest/content integration. KubeJS requires NeoForge 21.1.199+ and Rhino build 81+. |
| Better Advanced Tooltips | 2101.1.0-build.5 | Client only. Modrinth labels it required for KubeJS; the actual KubeJS descriptor calls it optional/client. Included with client tooling, excluded from the server. |
| Architectury | Existing 13.0.11 retained; same artifact for clients | Both sides. Satisfies the FTB and GriefLogger version floors. |
| JEI | 19.53.0.425 | Both sides. Minimum matching XMod 21.1.12's optional-if-present dependency; replaces installed 19.51.0.418. The release is labeled beta upstream. |

Exact artifact sources, SHA-512/SHA-256 hashes and installation paths are recorded in the [artifact lock](ecosystem-mods-1.21.1.lock.json) and [installation receipt](ecosystem-installation-2026-10-13.json). Runtime data and private mod JARs are not committed to this repository.

## Ordered adaptation backlog

Progress 2026-10-18, UTC+08:00: [native monetary rewards](ftb-monetary-rewards-2026-10-18.md)
bind explicit CustomRewards to configured exact Economy amounts. Durable pre-reset
intent, native acceptance, immutable beneficiary and public receipt replay cover
repeat/party changes and callback failures. Actual process kill after ledger commit
recovers one payment and fences the older unsaved FTB cycle. Commerce task bindings,
all kill points, financial backup recovery, full reload/reconnect and integrated
client/migration acceptance remain required; ADAPT-04/05/11 are not closed.

Progress 2026-10-18, UTC+08:00: the user selected **Create (mechanical power)**
as the machine provider. The actual pack's Create 6.0.10 performance-v6 now has an
[optional native processing recipe adapter](create-processing-adapter-2026-10-18.md)
for eleven process types, including fluids, probability, components and reload
revisions. Table evidence capability remains distinct; machine attribution,
assembly and durable reward/crash acceptance remain open under CRAFT-03–05.

Subsequent [native millstone evidence](create-millstone-evidence-2026-10-18.md)
adds authenticated persistent beneficiary assignment, actual recipe-instance/random
result observations and component-sensitive input/output/remainder verification.
Owned FTB CustomTasks receive actual primary-output units through the existing
durable journal and absolute projection. Fifty-four native checks pass on each
NeoForge target; other process evidence, offline credit, monetary generations and
actual process-kill/world rollback acceptance remain open.

Progress 2026-10-18, UTC+08:00: [configured Paradigm exports](paradigm-provider-exports-2026-10-18.md)
add exact native currency balances and visibility/permission-filtered current-team
FTB progress/maximum/completion. Worker callbacks use immutable snapshots; owned
registration handles include configured keys and retire on provider replacement.
Inbound evaluation still requires a supported Paradigm contract; ADAPT-07's full
formatting/provider reload and release acceptance remain separate.

Progress 2026-10-18, UTC+08:00: [explicit FTB craft bindings](ftb-craft-evidence-projection-2026-10-18.md)
connect confirmed exact-recipe evidence to owned CustomTasks. Bindings retain stable
epochs, recipe revisions, creator UUIDs and native definitions; current-team
admission and absolute projection retain distinct repeat generations. Failure
review, rollback fencing and authenticated reconciliation are implemented. Monetary
rewards, commerce tasks, machines, actual process-kill/rollback and specialist/client
acceptance remain open under ADAPT-04/05/11.

Progress 2026-10-18, UTC+08:00: [crafting result observations](crafting-evidence-2026-10-18.md)
add a native event source with actual selected recipe ID/revision, actor, immutable
input/output components and per-batch observation identity. It runs before native
input consumption; durable generation admission, journaling/reconciliation and
recipe-specific task application remain required under CRAFT-04/05.

Progress 2026-10-18, UTC+08:00: [FTB task discovery](ftb-task-discovery-2026-10-18.md)
adds native visibility-filtered pagination, details, prerequisites, sequential order,
repeatability observations and reward claim ownership/status to NPC commands and
dialogue. It does not establish a durable monetary generation or apply craft/commerce
evidence; those requirements remain open.

Progress 2026-10-18, UTC+08:00: [native recipe discovery](crafting-recipe-discovery-2026-10-18.md)
provides registry IDs, component-sensitive output lookup, native ingredient predicates,
tag/candidate snapshots and revision checks for NPC/dialogue guidance. The selected
KubeJS script and actual server resource reload are exercised natively. Machine
adapters, attributed craft evidence and durable monetary rewards remain open.

Progress 2026-10-18, UTC+08:00: [RSItemEditor library references](rsitemeditor-adapter-2026-10-18.md)
add shared/personal resolution, defensive native snapshots, explicit NPC/dialogue
grants and live provider permission checks. The combined FTB/RSItemEditor audit
passes 31 checks with actual Paradigm authority; the optional-absence/provider
service audit passes 123 checks. [Legacy conversion](legacy-rsitemeditor-migration-2026-10-18.md)
now has preview/apply, exact source backups and non-overwriting native shared imports;
missing legacy content still needs authoritative source/provider resolution. The
[FTB repeatable reward boundary](ftb-reward-claim-boundary-2026-10-18.md) records
why a simple monetary callback cannot close the durable reward requirement.

Progress 2026-10-18, UTC+08:00: [native FTB task/delivery adapter](ftb-task-adapter-2026-10-18.md)
adds authoritative current-team queries, consuming item delivery commands for
ordinary/packet NPCs, and dedicated dialogue actions/conditions. It passes 17
native checks. Economy 0.5.5 (`605e187`) adds durable receipt history and commit
notifications, passing 89 unit/41 native checks; existing consumers pass 121
checks. Quest receipt application, monetary generations/rewards, crafting and
process-crash reconciliation remain open.

Progress 2026-10-18, UTC+08:00: [accepted commerce API](economy-commerce-api-2026-10-18.md)
adds public read-only quotes, exact accepted execution, outcome/claim access and
retired-provider rejection in Economy 0.5.4. Validation passes 86 provider unit
tests, 35 provider native checks and 121 actual-provider consumer checks.
ADAPT-03 remains open for crash reconciliation and durable receipt subscriptions.

Progress 2026-10-18, UTC+08:00: [Economy commerce recovery](economy-commerce-recovery-2026-10-18.md)
completes the interrupted 0.5.3 caught-failure fix, including exact stack counts,
committed receipts, recovery records and full-inventory claim retries. It passes
24 native commerce checks and 121 actual-provider consumer checks. ADAPT-03
remains open for public quotes/execution, full reconciliation and crash durability;
quest/crafting and the other adopted integrations remain open as well.

Progress 2026-10-16, UTC+08:00: [system-menu consumer adaptation](economy-menu-adapter-2026-10-16.md)
adds native NPC commands and dedicated dialogue preflight/execution for Economy's
browser/shop/page/search/quantity menus. Provider acquisition is per request and
retired providers cannot be reused. ADAPT-01/02 remain open for coordinate shops,
their access policy and wider release acceptance. [Command retirement](command-retirement-2026-10-16.md)
and [admission/payment/permission boundaries](command-admission-2026-10-16.md) are
now implemented and validated separately.

[Coordinate-shop adaptation](economy-coordinate-adapter-2026-10-16.md) extends
Economy to 0.5.2 and the consumers to local coordinate targets, with exact ID/block
lookup and current management authority on each click. The API's local policy
is documented in Economy's `docs/coordinate-shop-api.md`; no remote permission
or deployment/physical-client acceptance is implied. ADAPT-03 commerce/receipts
and ADAPT-04/05 quest/crafting are the next integration requirements.

Progress 2026-10-18, UTC+08:00: [public script and FTB XMod contracts](kubejs-ftb-script-contracts-2026-10-18.md)
expose current recipe/visible task queries, explicit native delivery and Economy
menus without a KubeJS dependency in Citizens. The four actual Rhino callbacks
pass 25 checks, including repeat/reset and native craft evidence, on 21.1.248.
The selected JEI/Economy floors prevent that complete fixture on 21.1.233;
ordinary baseline compatibility remains separate. Missing XMod/Economy regression
passes 139 checks. Durable monetary claims and integrated release gates remain.

Progress 2026-10-18, UTC+08:00: [Guard Villagers coexistence](guard-villagers-coexistence-2026-10-18.md)
protects NPC recruitment/AI identity and skips direct assistance target assignments
for Citizens-owned AI. Native guard recruitment, equipment, follow/patrol payloads
and physical goal movement, targeting, protection and friendly fire pass 41 native
checks on each NeoForge target. Missing-provider regression passes 137 checks.
Physical clients, full-world restart and combined migration acceptance remain open.

All rows are accepted goals. An unchecked row means adaptation remains to be implemented/validated, even when its provider is installed.

Progress 2026-10-18, UTC+08:00: [native commerce receipt tasks](ftb-commerce-tasks-2026-10-18.md)
add exact system/coordinate buy/sell bindings, precommit team/cycle attribution,
durable receipt replay and absolute native projection. Actual selected FTB/XMod/
Rhino/Economy validation passes 27 commerce plus 44 existing checks; absent-Economy
regression passes 141 checks. Three actual JVM kill stages recover the original
settlement/task/delivery with six checks each. Ordinary baseline passes 767 tests.
The final checkpoint manifest version also passes the APPLIED kill/restart stage.
Commerce capability 3 is provided by Economy 0.5.6. Additional Create machines,
multi-member/offline recovery and full migration acceptance remain open.

| Order / ID | Owner and task | Completion gate |
|---|---|---|
| 1 / ADAPT-01 | Economy: publish the [ordered Economy API backlog](yuuniverse-economy-gui-api-requirements-2026-10-12.md), starting with ECO-01–03. | Stable shop lookup and Economy-owned browser/system/coordinate menu entry points exist in a versioned provider JAR. Old/absent providers fail without payment. NPC/dialogue paths use the same menus as Economy commands. |
| 2 / ADAPT-02 | Citizens + Interactions: bind typed shop references and optional provider lifecycle. | Ordinary and packet NPC clicks and dialogue actions open the intended system/player shop; invalid IDs, permissions, distance, disconnect and provider retirement have defined results. Keep dialogue code in its own JAR and retain distinct Citizens-owned inventory-shop functionality. |
| 3 / ADAPT-03 | Economy: expose quotes, execution, receipts, pending deliveries and post-commit reconciliation (ECO-04–08). | One system-shop buy/sell and one finite player-shop trade settle exact money, item components, stock, tax and limits; duplicate requests and full inventories recover across restart without duplicate money/items. |
| 4 / ADAPT-04 | FTB Quests + Interactions: task query/advancement and monetary reward adapters. | One NPC delivery and dialogue condition share authoritative player/team state. Item consumption, party changes, repeatable quests and reward generation have explicit semantics. Imported Bukkit quest references are mapped with provenance; unsupported content is reported. |
| 5 / ADAPT-05 | KubeJS + FTB XMod Compat: supported script bindings and real mod-crafting task support. | Expose only public Citizens/Economy/FTB contracts. Verify version-specific `FTBQuestsEvents.customTask`, `customReward`, `started`, `completed` bindings. Implement CRAFT-01–06 in the Economy API document: registry recipe lookup, supported machine adapters, actual craft evidence and repeat-safe progress/rewards. Buying an item cannot masquerade as crafting it. |
| 6 / ADAPT-06 | RSItemEditor: item-library adapter and legacy ItemEdit content conversion. | Preserve complete components, quantities and shared/personal ownership. Resolve saved-item references explicitly and return defensive copies. Deletion/redefinition follows a stated snapshot/reference policy; missing mods/items retain recoverable source data. Check Paradigm command permissions. |
| 7 / ADAPT-07 | Paradigm: retain owned NPC placeholder exports and add configured Economy/quest values. | Registration handles release on shutdown/provider replacement; worker-thread formatting uses immutable snapshots. Resolve economy/quest data from their owners. Installed 2.5.0's `PlaceholderService` has registration only; inbound text evaluation needs a separately supported API, not private formatter reflection. |
| 8 / ADAPT-08 | Guard Villagers: coexistence and explicit guard workflows. | Test spawn, recruitment, equipment, patrol/follow, faction/target policy, protection and friendly fire around Citizens NPCs. Document which entities own combat. Do not attach Guard Villagers AI to Citizens through guessed internals or claim full Sentinel parity. |
| 9 / ADAPT-09 | NeoProtect + GriefLogger: compatible logging and attribution boundaries. | Both mods load together with Economy. Test ordinary/modded blocks, inventories and entities, operator permissions, lookup, retention, restart and throughput. NeoProtect owns selected world rollback; GriefLogger supplies independent logs. Define duplicate-event handling and correlate Economy receipts; world rollback must not silently reverse ledger payments. |
| 10 / ADAPT-10 | Vivecraft: native VR/non-VR interaction acceptance. | Verify VR aim/reach and both-hand NPC interaction, container/menu clicks, teleport/mounts, dialogue/cameras and reconnect with the target pack. Check server policy and ordinary-client coexistence; do not assume the Spigot extension's data/config transfers. |
| 11 / ADAPT-11 | Integrated migration and release acceptance. | Pin server/client/provider hashes, reconcile old IDs/accounts/items and migrated data, test permissions/quests/economy across reconnect and restart, and run physical VR/non-VR clients. Record exact limitations and rollback files before claiming full migration. |

ADAPT-01–05 provide the first usable NPC → shop → quest/craft → reward workflow. The specialist integrations can proceed after their contracts are known. The existing delayed-command and transaction correctness work in [remaining Citizens work](remaining-citizens-work-2026-09-26.md) remains required; selecting these mods does not mark core parity complete.

## Installation and validation record

Official Modrinth release metadata and FTB Maven Gradle module metadata supplied download URLs and SHA-512 hashes. JAR descriptors were inspected for mod IDs, loader versions and transitive requirements. Inspection found XMod's JEI floor and the client-only tooltip distinction that project-level dependency lists alone omit.

An isolated copy of the deployed mod JAR set, a fresh synthetic world and fresh configuration is used for startup checks on 21.1.248. Production worlds, permission databases, economy ledgers and configuration are not copied into the fixture. The live server process is not restarted by this task. File installation takes effect on its next restart; physical-client acceptance and integration implementation remain separate gates.

The unmodified GriefLogger release fails during module resolution: `Modules grieflogger and org.xerial.sqlitejdbc export package org.sqlite`. Its 1.21.1 source at `e913b78806a8f7646b7e3adc0d6721d1b386f29d` flattens SQLite 3.47.2.0 into the root JAR via `shadowBundle`. Economy already includes SQLite as a selectable Jar-in-Jar dependency with range `[3.46.1.3,)`. The [source patch and rebuild instructions](../tools/grieflogger-compat/README.md) switch the driver to Loom's `include` and mark the custom version. The built JAR retains the complete JDBC dependency and its native resources; module resolution selects one SQLite 3.47.2.0 module.

**Installed at 2026-10-13 10:52:09 +08:00 (UTC+08:00):** 35 artifact copies across the three destinations, with two identical Architectury copies retained. The server now contains 65 mod JARs. The hosted AutoModpack source at `E:/yunniverse-server/automodpack/host-modpack/main/mods` contains 57. The supplemental `client-pack/mods` folder previously contained only one MTR JAR despite its stale 42-JAR README; it now contains 12 and is not a complete client pack. Both client destinations contain the 11 selected client integration/dependency JARs. AutoModpack regeneration/client synchronization and loading new server mods await the next normal restart.

Old JEI server and hosted-client JARs are preserved under `E:/yunniverse-server/archive/ecosystem-2026-10-13/{server,hosted-client}`. GriefLogger source provenance and the patch are retained under `E:/yunniverse-server/source-patches/grieflogger-2026-10-13`. Retained Citizens, Interactions, Economy and Paradigm artifacts were not replaced in this batch. The user's subsequent authorization to update Economy from v0.5.0 is a separate follow-up batch.

Validation: GriefLogger `:neoforge:build` succeeds. The unchanged release fails module resolution; the patched full 65-JAR server set reaches `Done`, registers the queried FTB/KubeJS/GriefLogger/shop commands, initializes GriefLogger, NeoProtect and Economy databases, and exits with code 0 after `stop`. Read-only SQLite `quick_check` returns `ok` for the fixture's GriefLogger, Economy and Paradigm databases. Logs retain existing/default-fixture issues involving client-class probing, MTS/MTR messages, a generated Paradigm hologram configuration, missing liquor recipes/tags and slow WorldEdit/CopyFoxies state enumeration. This is startup/database evidence, not clean-modpack, logging gameplay, migration, GUI, VR or physical-client acceptance.

Local initial-installation evidence: `artifacts/ecosystem-2026-10-13/{server-smoke-grieflogger-conflict.log,grieflogger-build.log,server-smoke-economy041.log,server-smoke-economy041-result.json,database-checks.json,installation-receipt.json}`. The tracked installation receipt includes the key evidence hashes.

### Authorized v0.5.0 follow-up

The [preparation record](economy-v050-preparation-2026-10-13.json) pins the package at `E:/yunniverse-server/updates/economy-0.5.0-2026-10-13`. The Economy repository's `docs/gui-upgrade-2026-10-13.md` documents activation and rollback; `docs/shop-integration-roadmap.md` carries the adopted API backlog. The package includes its own README and scripts. It is prepared for a planned stop, not activated on the running server.

Validation: the v0.5.0 clean build passes 79 tests; the complete selected mod set starts/stops with exit 0; the actual-provider Citizens/Interactions audit passes 36 checks for shop menus, preflight/payment boundaries, currencies, offline identities and camera coexistence. The ordinary Citizens artifacts were rebuilt without the opt-in service probe afterward; they were not deployed or committed over the pre-existing gameplay draft.

An online read-only backup was used solely for rehearsal. The upgrade migrates schema 8→9 and restores 40 source-verified shop sections/88 pages; all 26 original tables outside schema/presentation/audit bookkeeping retain their row hashes, including money, transactions and coordinate shops. The portable upgrade/rollback restores the exact original database and artifact hashes. The production update takes a fresh stopped-server backup and enriches the ledger present at activation time. The package contains no rehearsal/player database. Remaining adapters and physical-client/gameplay acceptance are still the future goals listed above.

## Primary sources

- [FTB Quests source for 1.21.1](https://github.com/FTBTeam/FTB-Quests/tree/1.21.1/main), [FTB Maven](https://maven.ftb.dev/releases/dev/ftb/mods/), [FTB XMod events](https://github.com/FTBTeam/FTB-XMod-Compat/blob/51c6f83b55993c085ab709199a0d20e949f4233f/neoforge/src/main/java/dev/ftb/mods/ftbxmodcompat/neoforge/ftbquests/kubejs/FTBQuestsKubeJSEvents.java).
- [RSItemEditor](https://modrinth.com/mod/rsitemeditor/version/KEpGvGgz), [Guard Villagers](https://modrinth.com/mod/guard-villagers/version/JyXugpy2), [NeoProtect](https://modrinth.com/mod/neoprotect/version/TK4G6K3x), [GriefLogger](https://modrinth.com/mod/grieflogger/version/IrRNGJIR), [GriefLogger pinned 1.21.1 source](https://github.com/DAQEM/GriefLogger/tree/e913b78806a8f7646b7e3adc0d6721d1b386f29d).
- [Vivecraft](https://modrinth.com/mod/vivecraft/version/LjqlbRAO), [KubeJS](https://modrinth.com/mod/kubejs/version/THIGFPwf), [JEI compatibility version](https://modrinth.com/mod/jei/version/5ULZJRXC).
- [Existing Citizens placeholder bridge](paradigm-npc-placeholders-2026-09-23.md), [Economy API requirements and crafting task contracts](yuuniverse-economy-gui-api-requirements-2026-10-12.md).

Progress 2026-10-18, UTC+08:00: [craft outcomes and journal](quest-evidence-journal-2026-10-18.md)
add return-scoped native evidence, durable deduplication/generation storage,
append-only transitions and explicit review of interrupted/missing outcomes.
Production binding/projection and authenticated review are now implemented by the
[explicit craft binding adapter](ftb-craft-evidence-projection-2026-10-18.md).
Monetary claims and process-crash/world-rollback acceptance remain required;
CRAFT-04/05 are incomplete.
