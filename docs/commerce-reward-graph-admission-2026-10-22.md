# Commerce evidence admission through native reward prerequisites

Recorded 2026-10-22, UTC+08:00. Extends
[same-quest admission](commerce-direct-reward-admission-2026-10-22.md).

On `de08ce4`, an actual paid/delivered trade completed a commerce CustomTask and
its downstream quest. Restoring an earlier Economy database removed the original
settlement but retained newer FTB and consumer completion. The actual downstream
ItemReward issued a diamond and was marked claimed. The failing native log,
fixture, original operation/transaction identities and exact audit source remain
in `artifacts/graph-commerce-before-*`.

The pre-mark guard now traverses relevant native dependencies. During one
synchronous admission, native completed/started getters for the same team filter
their existing true values through validated commerce evidence. Outside that
scope they retain their original values. FTB's own dependency evaluator chooses
all/any/minimum and completed/started branches; its child evaluator chooses
required, optional and excluded children. Native task availability retains
flexible progression. Sequential task dependencies additionally validate their
preceding native tasks. Unrelated sibling tasks do not impose commerce ownership.

During filtered evaluation, the scoped wrapper for TeamData's dependency getter
directly evaluates the quest's public predicate. Filtered predicates neither trust
nor populate FTB's normal dependency cache. Binding inspection temporarily uses
ordinary native reads, preserving their normal cache behavior.
The context is removed in `finally`, including rejection and nested scopes.
Verification reuses successful predicates only within one admission. Visiting
cycles fail that branch; a cycle-truncated failure cannot poison a later valid
alternative. Validation writes no native progress or claimed marker and installs
no filtered dependency cache entry. It performs no reconciliation, trade, inventory
delivery, payment or monetary claim preparation.

Each relevant commerce task retains the same checks as direct admission: actual
original public provider metadata/outcome including offline contributors,
immutable definition, nonregressed cycle and exact confirmed/applied absolute
progress. An unused invalid alternative does not block a valid native path.
CONFIRMED automatic rewards and native no-op handling retain their existing
contracts. Item, command and monetary execution remains owned by the existing
native paths after admission.

An early draft rejected flexible quests despite FTB permitting their completion.
The retained `graph-flexible-before-*` reproduction motivated the native
availability/cache wrapper. A separate expanded-fixture setup failure came from
completing a dependent task before its newly added required sibling; the fixture
now completes that sibling first, and asserts every root's native claimability.
Neither diagnostic changes production progression rules.

The opt-in `rewardgraph` financial runner restores an actual saved seed team file
before each stopped financial phase, because valid alternative rewards are
allowed to mark claims during negative cases. One actual operation supplies six
distinct bound-task attributions. Twenty-one actual reward roots cover required,
transitive, task, chapter, partial-started, sequential, ANY, minimum, optional,
independent-sibling and flexible paths. Required item/command/money claims reject
missing settlements at lower/equal/higher sequences; valid alternatives issue
exactly once. A matching backup permits all claims with exact totals.

Exact final results, hashes, original identities, source/package ownership and
stopped read-only database integrity are recorded in
[the validation manifest](commerce-reward-graph-validation-2026-10-22.json).

| Final validation | Result |
|---|---|
| Native prerequisite financial-restore matrix | 277 assertions across five stages |
| Direct reward restore regression | 42 assertions |
| Actual party/offline contributor restore regression | 47 assertions |
| Selected XMod / commerce / party reward regression | 44 / 27 / 19 checks |
| Ordinary NeoForge 21.1.248 and 21.1.233 | 775 tests each; no failures/errors/skips |
| Separate Interactions suite | 100 tests; no failures/errors/skips |
| Release archives | Six checked; ownership/exclusions/source matches pass |
| Stopped financial databases/snapshots | Nine read-only integrity/operation checks pass |

The first evidence inspection used SQLite `mode=ro`, which created empty reader
WAL/SHM sidecars for two stopped final stores. Run markers, exclusive world/database
access and zero-length WAL were verified before removing only those reader
sidecars; sizes/hashes are retained. Final checks use `mode=ro&immutable=1` only
after asserting that the exclusively stopped snapshots have no sidecars. They
leave every database unchanged and verify original journal/operation identities.

The full migration remains unfinished. Separate monetary PAID settlement
inspection, unretained offline PREPARED discovery, removed teams, arbitrary
callback mutation, retention/storage/load and large-history throughput still
need work. Original quest mapping and ItemEdit 6/15/144 are unavailable; the
Eyephone 163 item and Paradigm inbound evaluator remain open. Physical ordinary,
VR and proxy acceptance remain open. The BenLi06 localhost fixture remains pinned
for the user's later test. No new process-kill or production activation is claimed.
