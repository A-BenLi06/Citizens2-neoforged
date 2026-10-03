# Native HPA graph debugging

`/waypoints hpa` and `/wp hpa` calculate a hierarchical path from the selected NPC's stored position to the player. They require an operator with `citizens.admin` and a selected NPC in the same dimension. The NPC may be unspawned if it has a valid stored location. A successful command reports graph/path statistics and writes the complete block path to the server log.

The operation is an independent diagnostic. It preserves the NPC's active navigation, waypoint provider, route and pause state. It does not select an HPA navigator. Interactions continues to own dialogue and cutscenes in its separate source set, mod ID and NeoForge JAR, with a one-way dependency on Citizens.

## Graph implementation

The seven native HPA classes follow the CitizensAPI cluster/portal design. The reference Git blobs and source URLs are recorded in `artifacts/hpa-source-inventory.json`; each downloaded file's Git blob hash is verified. Base clusters are 16 x 8 x 16 blocks, and each of three additional levels doubles every dimension. Loading regions remain 64 x 64 blocks. The 128-block level groups children on a global grid, including children from adjacent loading regions, instead of overlapping independent region-local clusters.

Exact grid indexing uses half-open cluster boundaries. Native block positions retain the full Y coordinate; no eight-bit Y packing or 0–255 world-height assumption remains. The vertical origin and extent come from the block source's actual dimension. `LevelBlockSource` exposes native height access, and a custom block source can supply explicit bounds to the graph constructor.

All valid boundary crossings become portals, including stairs that cross a horizontal and vertical cluster boundary simultaneously. This retains more portals than the reference's wide-entrance sampling heuristic, preserving narrow/stepped connections at the cost of a larger abstract graph. Base intra-cluster costs come from local shortest-path searches. Higher edges search only the lower graph inside their parent footprint. Upper clusters do not allocate block-volume caches.

Queries promote start and goal through the requested abstraction levels; the default uses the highest level. Existing entrance nodes are reused. Only query-owned memberships and edges are removed, deepest first, including on interruption. Abstract A* returns when the goal is settled, and recursive refinement validates every concrete segment. Missing or failed refinement produces an empty path. The diagnostic follows the original two-block-tall land-walking model, with shared endpoint, step headroom and diagonal corner checks throughout graph construction and refinement.

Dirty block/chunk notifications rebuild the requested regions and hierarchy. A chunk crossing the graph's unaligned origin marks every overlapping region. An interrupted build keeps the rebuild pending; interrupted queries release all temporary memberships.

## Native command execution

The command copies block-state palettes from loaded chunks on the server thread. The snapshot retains no live level, chunk or block-entity references. It distinguishes missing data from known air, preserves native height bounds, and provides native state/fluid/collision access. Snapshot capture never requests chunk loading or creates tickets. Expensive graph construction and search run on one server-owned worker thread.

The requested region rectangle includes both endpoints. These settings under `npc.pathfinding.hpa-debug` govern admission:

| Setting | Default | Behavior |
|---|---|---|
| `max-regions` | `4` | Maximum number of 64 x 64 regions in the endpoint rectangle. |
| `max-requests` | `4` | Maximum total pending requests, including the running search. |
| `timeout` | `30s` | Maximum lifetime of a pending request, including queue and calculation, using Citizens duration syntax. |

Nonpositive limits disable diagnostics. Malformed settings produce localized feedback. A newer accepted request from the same player cancels the old request and removes its queued work. Invalid requests do not replace a valid pending request. Completion and periodic cleanup validate the exact NPC, registered owning registry, player session, world, operator status, permission and request ownership. An exception from a permission or custom NPC provider retires the request, reports failure and logs the cause without escaping into the server tick. Shutdown cancels queued/running jobs and releases the executor. Command feedback uses translation keys.

A no-path result describes the captured region/chunk scope. It does not establish reachability outside that snapshot, account for subsequent world edits, or reproduce arbitrary NPC sizes and movement modes.

## Validation

The [isolated fixture](../tools/hpa-runtime-audit/README.md) passes **109 checks on each of NeoForge 21.1.248 and default 21.1.233**. The 16 deterministic algorithm tests pass within the full set of 249 ordinary tests. All 100 Interactions tests pass. Related regressions pass 127 waypoint-lifecycle, 81 movement/list, 129 follow and 62 removal checks; general validation passes 107 NPC assertions and 223 probes. Every Gradle/server invocation terminated before the next began.

Retained development diagnostics distinguish fixture mistakes from production behavior: the native registry factory signature, exception-based console rejection, player movement loading chunks, message highlight formatting and a nullable permission result required fixture corrections. The faulty callback also exposed a production exception boundary, now covered by an explicit provider-failure test. An intermediate 99-check run was not accepted: its permission-revocation case passed through the 30-second timeout. Final checks install and verify a real denial, require cancellation within five ticks, and reject timeout feedback for every stale-request case.

The clean test/build succeeds. Tested source/fixture hashes match the final implementation. Citizens binary/source archives include HPA and exclude audit classes. Interactions remains a separate JAR with unchanged entry names and contents against the preceding release. Original Citizens `saves.yml` remains unchanged. `artifacts/summarize-hpa.ps1` consolidates logs, reports, reference blobs, source checkpoints and archive hashes in `artifacts/hpa-validation-summary.json`.

The full Citizens goal retains the data, service, integration and physical-client/modpack/proxy acceptance items on the [current checklist](remaining-citizens-work-2026-09-26.md). This diagnostic does not establish ordinary navigator, custom movement-mode or full modpack parity.
