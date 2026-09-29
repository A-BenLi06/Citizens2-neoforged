# Powered, name and home command options

Target: Minecraft 1.21.1. Validation uses NeoForge 21.1.248; the declared default remains 21.1.233.

## Original behavior and native implementation

The checked-in Citizens command and HomeTrait sources establish the original `powered --set`, `name -h`, home `-h` and duration options. An explicit home location follows `-h`, and teleport follows pathfinding when both switches are present. An empty home command does not silently save the console or player's position. Existing explicit name values and home `-c` remain supported extensions.

Typed command validation runs before trait attachment or property changes. Invalid booleans, durations, overflow, nonfinite numbers, unknown worlds and unsupported options use the existing translated errors. Bare home durations retain tick units; other durations use Citizens' duration parser and must fit the persisted integer tick field. Clearing home now uses a translation key.

Powered setters and pre-spawn preparation apply the saved value to native creeper metadata before world admission. Retired traits cannot change a replacement entity. Name changes request a packet update; hover retains the native targetable name while disabling always-visible metadata. Physical-client drawing remains a separate check.

## Home routes and navigation ownership

Home pathfinding configures its own margins before strategy creation and navigation callbacks, using a new location-target overload accepting a parameter configurator. `getLocalParameters()` exposes the request's parameters during preparation and pause, and returns defaults after cancellation or failed preparation. Cancellation and replacement stop paused strategies as well as active ones.

The home trait remembers the exact parameter object for its route. Clear, strategy changes, removal and despawn cancel that route only while it still owns it; newer external requests survive. Home waits for pending and paused requests, prevents reentrant returns, and releases its guard after errors. The navigator rechecks request, entity and strategy ownership after factory, trait-admission and target-access callbacks before issuing a begin event. Failed preparation releases its parameters without disturbing a newer request.

Home retains original delay/distance behavior, uses a counter that cannot overflow the persisted maximum delay, copies destinations and rejects nonfinite API inputs. A pathfinding home in another dimension remains pending; only the selected teleport strategy can transfer dimensions.

## Validation

The recovered candidate passed 164 checks in `artifacts/command-options-contained248.log`. A fresh recovery run also passes 164 checks in `artifacts/command-options-recovered248.log`, followed by successful Gradle completion. Earlier negative and diagnostic logs are retained separately, including explicit power state and home callback failures.

Final sequential regressions pass 129 follow, 81 movement/list, 401 armorstand and 49 command-location checks, plus 107 NPC assertions and 223 general runtime probes. All 232 ordinary tests pass without failures/errors/skips, and clean build succeeds. The initial general-suite invocation named a nonexistent init script and stopped before server launch; its log is preserved separately from the corrected successful run. The evidence summarizer's initial ordered-dictionary count was corrected to use report objects; it then verifies all 232 XML-reported tests. No production change followed the dedicated run. Source checkpoints, logs, reports, archive exclusions, unchanged Interactions entry contents and the original saves hash are recorded in `artifacts/command-options-validation-summary.json` and `artifacts/summarize-command-options.ps1`.

The fixture uses native command dispatch, a permission provider and an admitted synthetic player with native packet encoding. It covers world/virtual NPCs, powered persistence/admission, hover metadata, atomic validation, ownership/type/selection, home duration and precedence, actual ticks, paused/pending routes, factory exceptions, reentry, removal and callback replacement. This does not establish physical-client input/rendering, arbitrary extension implementations, full-modpack acceptance or proxy arrival.

## Transfer recovery and next comparison

The earlier `send_to_server` batch is already on the current signed-history branch as `8f6db5da26a7a6eef8857e2c49ced8218e1b7fa6`; historical reports used its pre-rewrite hash `c2ed1b4`. The source trees are identical. Fresh read-only checks match all four retained log hashes and their completion markers in `artifacts/transfers-validation-summary.json`, including 51 transfer checks. Those are historical protocol results, not a new proxy-arrival test or validation of today's rebuilt archives.

Further comparison identifies two concrete follow-ups:

- Interactions lacks the original `SWAP_HAND_CLICK` entry mode. The original handler cancels swapping only after a successful conversation start and does not invoke click-to-skip. NeoForge's existing `LivingSwapItemsEvent.Hands` provides the native hook in both 21.1.233 and 21.1.248. Target selection should use native eye-ray geometry, block/entity occlusion and current world/virtual viewer eligibility. Actual packet-handler checks still need to cover success/refusal, hand preservation, native event modifications and reload/reconnection.
- Sentinel's unspawned respawn branch is unreachable from normal Citizens ticks, which skip unspawned NPCs. Natural death also leaves a controller entity reference that prevents a later spawn. The Citizens `RESPAWN_DELAY` metadata has no reader in the port. A coherent death lifecycle must distinguish accepted death from canceled death/drops, preserve native processing, release only the owning controller, and coordinate Citizens/Sentinel scheduling. Original Sentinel negative/zero/positive values respectively destroy, retain without respawning, or schedule after death. Manual despawn is not a death trigger. Existing direct-tick Sentinel health checks do not validate any of these cases.

CmdCam remains responsible for camera playback and Yuuniverse Economy for economic APIs. These follow-ups are comparison findings, not implemented or accepted behavior.
