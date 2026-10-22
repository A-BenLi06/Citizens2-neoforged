# Validate commerce backing before native rewards in the same quest

Recorded 2026-10-22, UTC+08:00. Extends
[retained receipt recovery](commerce-dependent-recovery-2026-10-22.md).

## Confirmed failure

On `043dd90`, a real paid/delivered commerce trade completes an owned CustomTask
and leaves native item, command and monetary rewards unclaimed. Restoring an
older Economy database keeps the newer FTB completion and consumer APPLIED
receipt. Although commerce reconciliation rejects the missing settlement, the
native reward path does not consult it. The native reproduction marks the item
reward as claimed despite the missing operation. The original failed assertion,
actual FTB claimed-reward entry, player file and audit source are retained.

## Admission behavior

Before `TeamData.claimReward` calls `markRewardAsClaimed`, Citizens checks whether
that reward's own quest contains commerce bindings. For such a quest, the actor
must have a current server session and native team, and FTB must report CAN_CLAIM.
Every retained WAITING/CONFIRMED/APPLIED contribution to those bindings for that
team is inspected through the supported Economy contract, including offline
contributors and earlier cycles. Original receipt/request payloads, journal
identity/high-water and settled-delivery policy remain authoritative.

Each binding must also retain its native definition and cycle. Current native
progress must equal its confirmed/applied absolute commerce total. Native progress
without retained consumer credit cannot authorize the reward. This validation
reads existing evidence; it does not reconcile progress, execute a trade, deliver
inventory, pay money, prepare a monetary claim or mark a native reward. Rejection
happens before FTB's claimed marker, repeat reset and reward callback. Matching
evidence leaves item/command execution to FTB and monetary settlement to its
existing journal/Economy path.

Automatic rewards can run synchronously while native task projection has installed
the confirmed total and has not yet acknowledged APPLIED. The guard accepts that
confirmed total after checking its actual settlement, without bypassing validation
for callbacks or replay. Commerce journals now open at ServerAboutToStart, before
FTB can load teams and attempt automatic claims. An unavailable journal cannot
silently authorize commerce-backed progress.

Locked, blocked and already-claimed native no-ops do not need another financial
inspection. A repeat retry for which FTB does not report CAN_CLAIM returns a no-op
without creating a monetary intent. Quests without their own commerce binding keep their native path,
including provider-outage behavior. Claims backed only by commerce prerequisites
in other quests are a separate remaining boundary: this change does not infer a
dependency graph or replace FTB's alternative/minimum/started dependency rules.

## Native acceptance

The guarded financial runner adds `-Scope reward` and `-Scope rewardshared`.
The first uses one actual buyer; the second uses a real two-member FTB party.
In the shared case, the selected older database retains the claimant's operation
but loses the offline member's later operation. Both tasks remain completed in
the newer native team file. Lower/equal/higher financial sequence cases check
actual item, command and monetary claims before native marking, preserving exact
money/items, command effects, claim journal and original commerce evidence.

A matching ledger checks actual native diamond issuance, a command issuing an
emerald and one monetary credit, then repeated no-op claims. Additional native
controls cover provider absence for unrelated/already-claimed rewards, automatic
claim during confirmed projection, two distinct repeat cycles and native progress
without commerce credit followed by explicit progress repair and a new real trade.

Exact results, source/provider/package/log hashes, native identities and stopped
read-only database checks are in
[the validation manifest](commerce-direct-reward-admission-validation-2026-10-22.json).

| Fresh validation against the final source | Result |
|---|---|
| Single native buyer and claims | 42 assertions across five stopped-backup stages |
| Native party with offline contributor | 47 assertions across five stopped-backup stages |
| Selected XMod / commerce / party rewards | 44 / 27 / 19 checks |
| Ordinary NeoForge 21.1.248 and 21.1.233 | 775 tests each; no failures, errors or skips |
| Separate Interactions suite | 100 tests; no failures, errors or skips |
| Ordinary release archives | Six inspected; final source matches, no audit/provider classes |
| Stopped SQLite databases/snapshots | Six read-only `quick_check` results are `ok` |

The initial broader regression caught a draft error: a duplicate attempt after
repeat reset threw instead of preserving a native no-op. That diagnostic log is
retained. The guard now returns false for that nonclaimable generation, and all
listed validation uses the corrected final source and fresh native worlds.

## Remaining acceptance

Reward admission through prerequisite quests, optional/minimum/started dependency
paths, chapter/task dependencies and arbitrary callback mutations still needs
implementation and native acceptance. Monetary PAID claims require their own
supported read-only settlement inspection after a financial restore; this guard
does not validate that separate journal. Fully offline unretained PREPARED
discovery, removed teams, throughput/storage/load, original migration inputs and
physical ordinary/VR/proxy workflows remain open. No new process-kill or production
activation is claimed. The user's physical-client fixture stays pinned for later.
