# Repository progress review

Reviewed: 2026-10-19T14:08:19+08:00 (UTC+08:00).
Gameplay baseline: `55396e644450a4f10ae2a3938c669c13fa25fbff` on `neoforge-port`.
Target: Minecraft 1.21.1, Java 21; NeoForge 21.1.233 core baseline and 21.1.248 selected ecosystem.

The Citizens core is broadly implemented and has extensive automated acceptance. The project is now working through native integration, recovery and migration acceptance. The complete migrated stack has not passed its release gates, and production still runs older Citizens/Interactions artifacts and Economy 0.4.1. A single completion percentage would mix implemented entry points, bounded integration checks and unverified physical-client behavior.

This review covers repository structure, native implementation boundaries, upstream references, command/trait declarations, build configuration, test suites, runtime-audit tooling, recorded acceptance, commit history, planning documents and the running server's startup inventory. It is a progress assessment with targeted source inspection, rather than a claim that every source line or every mod/client combination is correct.

## Repository and implementation boundaries

Update 2026-10-22, UTC+08:00: [delivery storage acceptance](delivery-storage-acceptance-2026-10-22.md)
adds real immutable-publication failures and damaged-history restoration tests
(13 focused tests per target), plus 17 native consumption/restart checks. Journals
of 1,024/4,096/16,384 deliveries retain all original chains and 43,008 revisions.
Measured warm reload and linear lookup/snapshot costs are recorded; broader
failure/retention/load and migration/client gates remain open.

Update 2026-10-22, UTC+08:00: [delivery checkpoint history](delivery-checkpoint-history-2026-10-22.md)
rejects saved checkpoints whose native outcome disappeared from a valid older
journal prefix, including absent-actor and latest-RESOLVED prefixes. Consistent
backups restore the original predecessor; no historical delivery is invented.
Fresh validation passes 285 native checks, 775 ordinary tests per target and 100
Interactions tests. Retention/load, other recovery and migration/client gates remain.

Update 2026-10-22, UTC+08:00: [delivery callback recovery](delivery-callback-recovery-2026-10-22.md)
prevents confirmation through held ItemTask/TeamData or retired actor objects
after native callbacks. Actual party deletion/replacement/disconnect cases retain
REVIEW, while original-party attribution and repeat reward resets remain native.
Storage/load, broader recovery, migration and client acceptance remain open.

Update 2026-10-22, UTC+08:00: [native evidence target mutation](evidence-target-mutation-2026-10-22.md)
fences retained TeamData for deleted native parties and checks live file/team/task/
quest identity and serialized definitions after completion callbacks. Uncertain
receipts retain their original attribution and CONFIRMED state. Separate delivery
submission callbacks, load/retention, migration and physical-client gates remain.

Update 2026-10-22, UTC+08:00: [offline PREPARED commerce recovery](commerce-prepared-recovery-2026-10-22.md)
discovers exact original receipts through Economy 0.5.9/Commerce capability 5
without an online owner or retained operation UUID. Missing evidence remains
PREPARED; pending delivery retains its native outcome. Original attribution is
never redirected to the administrator or a later party. Removed-team/callback,
load/retention, original migration and physical-client gates remain open.

Update 2026-10-22, UTC+08:00: [PAID monetary restore validation](monetary-paid-backup-recovery-2026-10-22.md)
verifies original public credit metadata/postings and the retained transaction UUID
before pending settlement or new claims in the same native team quest. Economy
0.5.8/API revision 5 provides supported read-only offline inspection (provider
`80bca9f`, ordinary95/native62). The remaining migration/client, unretained offline
discovery, removed-team/callback and load/retention gates stay open.

Update 2026-10-22, UTC+08:00: [commerce reward prerequisite admission](commerce-reward-graph-admission-2026-10-22.md)
extends validation through native quest, task, sequential, chapter and started
dependencies. FTB retains its alternative/minimum, optional-child and flexible
progression rules. Scoped predicate validation avoids reading or populating the
normal dependency cache. Monetary PAID settlement inspection, unretained offline
discovery and the remaining migration/client/recovery gates stay open.

Update 2026-10-22, UTC+08:00: [direct commerce reward admission](commerce-direct-reward-admission-2026-10-22.md)
checks same-quest commerce backing before FTB marks item, command or monetary
claims or resets repeat progress. Financial inspection includes offline party
contributors; confirmed projection can still trigger native automatic rewards.
Unrelated and already-claimed native paths preserve provider-outage behavior.
Admission through other-quest prerequisites, monetary PAID inspection and the
remaining migration/client/recovery gates are still open.

Update 2026-10-22, UTC+08:00: [dependent commerce recovery](commerce-dependent-recovery-2026-10-22.md)
recovers retained projections loaded before their native quest prerequisites or
sequential tasks. Batches retry only after a successful scope, keep native gates
and report remaining contextual failures after valid scopes recover. Financial
prevalidation remains before any projection. Native reward admission, unretained
offline operation discovery, removed teams, throughput and migration/client gates
remain open; exact fresh acceptance is recorded in the linked manifest.

Update 2026-10-22, UTC+08:00: [offline commerce reconciliation](commerce-offline-reconciliation-2026-10-22.md)
recovers retained absolute task progress through console administration with every
contributor offline, including settled WAITING deliveries. Current native team
scopes also participate in replay by noncontributing party members. The actual
stopped-task-file/financial-backup cases supplement the earlier contributor
validation; they do not establish offline discovery of unretained operations or
native/monetary reward backup correctness. Fresh ordinary builds pass 775 tests
per target, Interactions passes 100 and selected native regression passes 90.

Update 2026-10-22, UTC+08:00: [shared/offline commerce recovery](commerce-shared-backup-recovery-2026-10-22.md)
extends current-actor validation to all retained contributors in the relevant
task/team/cycle. Economy 0.5.7 capability 4 supplies the public offline inspection
contract (provider commit `1507c3d`, 91 ordinary tests and 54 native checks). The
actual two-member party fixture passes 40 stopped-backup checks. Both ordinary
Citizens targets pass 775 tests; 100 Interactions and 90 selected native checks pass.
Monetary/native reward admission, reconciliation with all contributors offline,
new noncontributing party members, throughput and physical/migration gates remain.

Update 2026-10-22, UTC+08:00: [financial backup recovery](commerce-financial-backup-recovery-2026-10-22.md)
closes the current-actor commerce replay gap where unrelated trades could hide a
missing settlement behind an equal/higher ledger sequence. Native stopped-restore
validation passes 39 checks with retained identities, unchanged native state on
rejection and matching-backup recovery. Fresh regression passes 90 native checks,
775 ordinary tests per target and 100 Interactions tests; six archives and three
read-only SQLite checks pass. Shared/offline receipt admission, monetary reward
backup validation and throughput remain open. No production activation occurred;
the user will run the pinned ordinary-client acceptance later.

The reviewed gameplay revision contains 2,047 tracked files, including 562 Citizens Java sources, 46 Interactions Java sources and 114 default test-source files. The trait factory declares 96 registered trait classes. There are 185 files under `docs`, 339 under `tools`, and 73 opt-in Gradle audit scripts.

| Area | Role and current implementation |
|---|---|
| `neoforge` | Active native Gradle project. NPC registry/lifecycle, events, traits, native entities, navigation, packet presentation, persistence, commands, inventory menus and extension APIs. |
| `neoforge/src/dialogue` | Independent Yuuniverse Interactions mod. Conversation parsing, sessions, progress, conditions, displays, action scheduling and camera/service integration. Its dependency is Interactions → Citizens. |
| `main`, `v1_21_R7`, `v26_1_R1`, `v26_2_R1`, `dist`, root `pom.xml` | Retained Bukkit/upstream comparison trees and Maven configuration. They are not the native build entry point. |
| `reference` | Ignored local upstream material, including CitizensAPI. Useful for porting comparisons, not part of the native production source set. |
| `tools` | Opt-in unit/parity suites, native synthetic-server fixtures, provider adapters for acceptance, migration inventory utilities and real JVM-kill runners. |
| `docs`, `walkthrough.md`, `artifacts` | Requirements and change history, plus local acceptance evidence. Most runtime logs/databases/JARs are ignored; selected validation manifests are committed. |

The native architecture uses Minecraft/NeoForge types and lifecycle events. Brigadier routes into the existing annotated command dispatcher. Vanilla container, merchant and anvil protocols provide the current GUIs. Optional Economy, FTB, Paradigm, Create, RSItemEditor and Guard bridges use supported provider surfaces or explicitly pinned mixin contracts. These boundaries require native consumers; matching package names do not give Bukkit binary compatibility.

The fresh declaration scan finds 192 upstream alias/modifier pairs and 201 native pairs. The only unmatched upstream pairs are `npc chicken`, `npc cow`, `npc pig`, `npc coppergolem`, `npc mannequin` and `npc sulfurcube`, which concern later-version entity features. Ordinary chicken/cow/pig NPC creation is supported. Historical missing 1.21.1 command names and HPA aliases have implementations. Declaration coverage does not prove option semantics or runtime parity.

## Current functional progress

| Workstream | Implemented and verified slices | Remaining acceptance |
|---|---|---|
| Citizens core | Creation, selection, removal/undo, skins/equipment, navigation/HPA, waypoints, follow/mount/pose, packet NPCs, holograms, shops, annotated commands, templates and behavior trees. Extensive ordinary/packet lifecycle, callback, persistence and reload fixtures. | Required provider combinations, external texture services, physical rendering/interpolation and integrated server/client acceptance. |
| Interactions | Separate mod/JAR with legacy dialogue parsing, conditions, progress retention, displays, inline/scheduled actions, native item/economy/quest operations and camera integration. The separate 100-test suite passes. | Full real conversation/content migration, camera clients, reconnect and actual proxy/backend `send_to_server` arrival. |
| ADAPT-01/02: Economy menus | System/browser/page/search/quantity and coordinate-shop lookup/opening for NPC commands and dialogue. Provider acquisition, session, permission and location checks are implemented. | Full deployed-provider/client workflows and the adopted integrated release gate. |
| ADAPT-03: commerce | Economy's accepted public API, exact quotes/execution, receipts, delivery outcomes and recovery are consumed through native adapters. Economy 0.5.6 supplies commerce capability 3. | Wider inventory-mutation crash points, financial-backup reconciliation and combined repeat/party/reconnect cases. |
| ADAPT-04: FTB tasks/rewards | Authoritative team-task query/discovery, consuming NPC/dialogue delivery, explicit craft/commerce CustomTask bindings, durable monetary CustomReward claims and original Economy receipt replay. | Legacy quest-ID/content provenance; multi-member/offline/reconnect behavior and the full reward/backup matrix. |
| ADAPT-05: scripts/crafting | Public Citizens script facade; actual KubeJS/Rhino `customTask`, `customReward`, `started`, `completed` callbacks. Native table/player-grid observations and durable recipe/team/cycle evidence. | Other required machine/assembly outcomes and the complete task/reward recovery matrix. |
| Create | Guidance for 11 processing types and sequenced assembly, including native ingredients/components/fluids/probability and reload revisions. Attributed completion evidence for millstones, saw cutting, crushing/milling, world/depot/belt pressing, basin mixing/compacting, spout/drain filling/emptying, depot/belt deploying, fan washing/haunting and final assembly, with native item/fluid/tool storage checks. Assembly recovery includes 24 actual process kills and 268 restart checks across both targets. | Additional machine/storage-interior kill points, earlier-step ownership and world/financial-backup combinations. |
| ADAPT-06: RSItemEditor | Shared/personal references, defensive native snapshots, explicit grants and permission checks. Source-backed preview/import preserves backups and conflicts. The fixture imports and reads back 139 of 140 original definitions; restart reports 139 unchanged and one unavailable. | `eyemod:eyephone` for package 163, missing source definitions 6/15/144, and other unresolved production content. No substitute definitions were invented. |
| ADAPT-07: Paradigm | Permissions/groups, owned NPC placeholders and configured exact Economy/FTB exports; immutable snapshots and registration retirement. | A supported inbound formatting/evaluation contract and wider reload/client acceptance. Installed 2.5.0's registration API is not an inbound evaluator. |
| ADAPT-08: guards | Native Guard Villagers ownership/coexistence and recruitment/equipment/follow/patrol/target/protection/friendly-fire workflows. 41 native checks on each NeoForge target. | Physical clients and full-world migration/restart. Sentinel's native melee subset does not establish complete ranged/admin Sentinel parity. |
| ADAPT-09: logging | Now committed in `55396e6`. Scoped NeoProtect duplicate multi-place-write correction; independent logger attribution, component-sensitive container/world rollback, finite Economy receipts and clean restart. 37 native + 8 restart + 141 absent-provider checks pass. | Retention tooling, queue overflow/storage failure, hard termination, wider inventories/entities/fire/explosions, production load and integrated clients. |
| ADAPT-10/11: VR and release | Selected provider installation, artifact locks, synthetic-stack startup and bounded native integration evidence exist. | Physical VR/non-VR interaction, both hands/reach, menus/cameras/mounts/dimensions, reconnect, actual migration data and release/rollback acceptance. These gates remain open. |

The latest progress is substantive: durable quest money, commerce receipt tasks, machine batch evidence and logging/rollback are no longer merely proposed adapters. Their broader acceptance gates still remain open. The [adopted backlog](ecosystem-adaptation-goals-2026-10-13.md) supplies the scope; older paragraphs describing these features as absent are historical.

## Verification during this review

PowerShell 7.6.5 and the available JDK 21.0.11 were used. Fresh final builds use `artifacts/progress-audit-final-build` to avoid sharing output with the concurrently running logging acceptance.

| Fresh verification | Result | Local evidence |
|---|---|---|
| NeoForge 21.1.233 ordinary test/build, including the final logging mixin source | 767 tests in 112 suites; zero failures, errors or skips; build successful | `artifacts/progress-audit-final-baseline-2026-10-19.log`, `artifacts/progress-audit-final-baseline-tests-2026-10-19.json` |
| NeoForge 21.1.248 ordinary test/build, same production Java sources | 767 tests in 112 suites; zero failures, errors or skips; build successful | `artifacts/progress-audit-final-selected-2026-10-19.log`, `artifacts/progress-audit-final-selected-tests-2026-10-19.json` |
| Interactions opt-in suite on 21.1.248 | 100 tests in 9 suites; zero failures, errors or skips | `artifacts/progress-audit-dialogue-retry-2026-10-19.log`, `artifacts/progress-audit-dialogue-tests-2026-10-19.json` |
| Binary/source archive inspection | Citizens and Interactions remain separate; no runtime probes or foreign provider implementation classes in the inspected ordinary packages | `artifacts/progress-audit-final-packages-2026-10-19.json` and this review's validation record |

The ordinary `test` task covers `neoforge/src/test`; it does not include the separate `tools/audit-tests` dialogue suite. That suite needs `tools/audit-tests.gradle`. An initial review invocation used an incorrectly relative init-script path and failed before executing tests; the absolute-path retry above passed.

The [review validation manifest](repository-progress-validation-2026-10-19.json) preserves the final test summaries, inspected package hashes, deployed artifact hashes and evidence-file hashes. These summarize this review's checks separately from the individual implementation acceptance records.

Recorded native/crash evidence was inspected separately from the fresh unit/build results:

- Create: 54 original plus 23 batch checks on each target; three actual PREPARED/CONFIRMED/APPLIED JVM-kill recovery stages with six checks each. All ten log hashes in the current Create batch manifest match retained files.
- FTB commerce: 27 commerce plus 44 existing selected-stack checks, 141 absent-provider checks and three six-check kill/restart stages. The final checkpoint-manifest implementation has an additional successful APPLIED-stage verification; the manifest does not claim every possible crash point.
- FTB monetary rewards: 44 selected-stack checks and five restart checks after a real ledger-commit/lost-ack kill. Party/offline and other financial-backup domains remain explicit limits.
- Logging: the work completed during this review. All 22 provider, source, log and package hashes in [its committed validation record](logging-attribution-validation-2026-10-19.json) match the inspected files. Clean restart is distinct from hard-kill durability.

Native fixtures execute real server code with synthetic actors and isolated data. They establish their stated contracts, not physical client authentication, rendering, controller input or unrestricted cross-file atomicity. The whole world, FTB state and financial/evidence stores do not form one atomic transaction. The supported recovery fences must be preserved when selecting backup/rollback files.

## Deployed state

The running production startup log identifies Minecraft 1.21.1, NeoForge 21.1.248 and Java 21.0.11, started on 2026-10-19 at approximately 00:11:49 UTC+08:00. FTB, KubeJS, RSItemEditor, Guard Villagers, NeoProtect, repaired GriefLogger, Vivecraft, Create and Paradigm appear in its loaded inventory. The October 13 installation receipt's blanket restart-pending wording is therefore an older observation.

However, the server still loads Economy **0.4.1-SNAPSHOT** and Interactions **0.0NONE**, and its Citizens/Interactions JAR hashes remain the older deployed hashes:

| Deployed file family | Bytes | SHA-256 |
|---|---:|---|
| Citizens | 2,527,097 | `73e82636cc6da1cdf534a6666b43d8553ee7458eb38b18bd685c45e3dd8a6673` |
| Interactions | 60,101 | `3bc4bdff01f94473395e6e52d3dd5ca3c31e3d06a55c38623187d8712a12e113` |
| Economy 0.4.1 | 14,357,004 | `f6f6fea0c4b6e1f7efa7ac25dbf8fa2ecf9e3a9cc29682cd7dff53132e283d2f` |

The tested current Citizens/Interactions binaries are approximately 3,964,037 and 204,123 bytes. Newer integrations therefore must not be assumed to be available on the live server. The prepared Economy 0.5.0 upgrade is also older than the now-accepted 0.5.6 commerce contract; a release decision needs an explicit compatible artifact set.

The complete selected provider fixture uses 21.1.248: Economy 0.5.6 requires 21.1.247+, and selected JEI requires 21.1.238+. The 21.1.233 core baseline passes independently. A passing core test build on 21.1.233 does not make that complete provider set compatible with it.

## Development process assessment

The project has strong local evidence discipline: reproduced failures are retained, callback/ownership/error boundaries have focused regressions, provider and package hashes are recorded, probes are opt-in, and real restart/process-kill checks supplement unit tests. The 233 local commits ahead of the verified remote/tracking revision `e0869af` all use `A-BenLi06 <ben_li06@outlook.com>` and include `walkthrough.md`. The count excludes this review's documentation commit. The interrupted logging draft became a complete bounded commit during the review; the remaining untracked `_nul` and `tools/__pycache__/` are local residue rather than gameplay work.

Four process gaps matter for the next stage:

1. **Build isolation:** simultaneous audit builds shared `neoforge/build`, and a native run failed after another build changed its classes. Dedicated output directories resolved the rerun. Concurrent chats/fixtures should keep their build directories and test reports separate.
2. **Acceptance orchestration:** no checked-in `.github/workflows` CI exists. Core tests, dialogue tests and 73 opt-in native audits require separate invocations; one green ordinary build is not a whole-stack acceptance result.
3. **Current status documentation:** dated documents often append progress while retaining superseded pending clauses. `citizens-next-work-2026-10-12.md` still contains its historical retirement draft and 194-command observation; `CONTRIBUTING.md` describes upstream Java 8/Maven instead of native Java 21/Gradle. The original `.claude` phase plan is historical. A current acceptance matrix should link its evidence and distinguish implemented, fixture-accepted, physically accepted and deployed states.
4. **Release provenance and publication:** most evidence lives in ignored local output, and 233 gameplay/development commits are ahead of the remote at the snapshot. Keep release-facing manifests and instructions durable, verify artifacts by hash rather than the unchanged SNAPSHOT filename, and finish publication/deployment as its own authorized step.

The pinned Gradle 8.14.3 build passes but emits Gradle-9 deprecation warnings. This is toolchain maintenance work, not a current build failure. Static TODO markers and deliberately inaccessible constructors likewise are not reliable backlog counts; for example, command registration is implemented despite its old P7 comment.

## Next acceptance batches

Follow-up 2026-10-19, UTC+08:00: [sequenced assembly evidence](create-assembly-evidence-2026-10-19.md)
adds separate weighted/ordered discovery and exact native final parent results.
Forty-one additional checks pass on both targets, including powered world/depot/belt
pressing, press → deployer → spout → saw, tool/fluid mutations, incidental matching
step output exclusion, parent identity/revision replacement, native remainder/storage
failures and journal reopen. Final-machine beneficiary attribution is explicit;
durable earlier-step owner history and machine-specific hard-kill/rollback remain open.

Follow-up 2026-10-19, UTC+08:00: [fan evidence](create-fan-evidence-2026-10-19.md)
adds native SPLASHING/HAUNTING world/depot/belt confirmation, exact accepted overflow
and complete simultaneous callback storage. Thirty-one additional checks cover
actual rolls/remainders, mutations, interruptions, unidentified concurrent recipes,
current revisions and reopened progress. Assembly and recovery/client gates stay open.

Follow-up 2026-10-19, UTC+08:00: [deployer evidence](create-deployer-evidence-2026-10-19.md)
extends native recipe confirmation through complete depot/belt storage and actual
tool retention/damage/consumption/remainder handling. Twenty-six checks cover native
power, outputs, drops, mutations, exceptions, offline attribution and journal replay.
Fan/assembly and wider recovery/client gates remain open.

Follow-up 2026-10-19, UTC+08:00: [filling/emptying evidence](create-fluid-machine-evidence-2026-10-19.md)
verifies native spout depot/belt outputs and item-drain held-item/tank deltas.
Twenty-eight additional checks on both targets cover actual rolls, simulation
exclusion, fluid-only progress, storage mutation, offline attribution, current
recipe replacement and journal reopen. Fan/deployer/assembly and broader
crash/recovery and integrated client acceptance remain open.

Follow-up 2026-10-19, UTC+08:00: [basin mixing/compacting](create-basin-evidence-2026-10-19.md)
now has scoped native multi-item/fluid evidence, exact storage confirmation and
primary millibucket progression for fluid-only recipes. The reproduced empty
dummy input is repaired for real basin remainders. Twenty-three additional native
checks cover direct/spout, heated, pure-fluid and attribution/mutation paths.
Other machine, crash/recovery and integrated acceptance below remain open.

Follow-up 2026-10-19, UTC+08:00: [depot/belt pressing evidence](create-transport-press-evidence-2026-10-19.md)
now observes the complete native callback through exact storage verification and
passes 21 additional native checks on both NeoForge targets. Remaining machine,
transport crash/recovery and integrated release gates below stay open.

1. Extend machine-specific hard-kill/inventory rollback beyond the accepted assembly boundary matrix, and extend actual evidence to other required unsupported machine types while preserving provider-native outputs and recipe revisions. See [assembly recovery](create-assembly-recovery-2026-10-20.md) for the 2026-10-22 review.
2. Extend the accepted [two-member reward/reconnect/repeat cases](ftb-party-reward-acceptance-2026-10-22.md) and [native delivery recovery](ftb-delivery-recovery-2026-10-22.md) to broader multi-member task and financial/world-backup combinations. The delivery matrix covers native progress/inventory mutation, confirmed outcomes and independently older player/task files; it does not automatically repair inconsistent owners.
3. Resolve original quest IDs, group/account/shop identities and missing legacy items with source provenance; keep unsupported content recoverable.
4. Extend [native fire/environmental logging](native-fire-logging-2026-10-22.md) and [lava ignition](native-lava-logging-2026-10-22.md) with required retention/failure/load coverage and obtain a supported Paradigm inbound contract where the pack requires one.
5. Run the complete migrated server/client workflow with physical VR and ordinary clients, external skins, camera playback, menus, mounts/dimensions and proxy/backend transfers.
6. Freeze compatible server/client/provider hashes and acceptance limits, prepare the matching stopped-server backup/rollback set, and complete the release/deployment decision.

These are the remaining adopted completion gates. Broad implementation and successful bounded fixtures justify substantial progress; they do not yet justify declaring the entire port or live migration complete.
