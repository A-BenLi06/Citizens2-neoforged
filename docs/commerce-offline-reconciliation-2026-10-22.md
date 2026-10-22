# Reconcile retained commerce evidence without contributor sessions

Recorded 2026-10-22, UTC+08:00. Extends
[shared contribution validation](commerce-shared-backup-recovery-2026-10-22.md).
Offline inspection requires Economy 0.5.7 / Commerce capability 4.

## Recovery behavior

The old administrator reconcile command only called replay for online players.
With no players online, it returned zero without checking retained settlements
or restoring native task progress. A stopped-restore fixture reproduces this on
`8ade678`: both APPLIED receipts survive, the actual older FTB team file contains
only one unit, and the selected ledger has lost the second receipt. Reconcile
returns success instead of rejecting the inconsistent backup.

Administrator reconciliation now validates all retained WAITING/CONFIRMED/APPLIED
settlements before processing any new history. It replays available online history,
then revalidates retained evidence, confirms settled WAITING receipts and projects
each original binding/team/cycle directly. It uses supported offline receipt
inspection and the original native team UUID. It never manufactures a player
session or substitutes the administrator as beneficiary/contributor. Nested
administrator reconciliation during another replay/reconcile is rejected.

Regular online replay also includes the actor's current native team/cycles as
well as their historical attributed scopes. A party member with no previous trade
can therefore recover shared progress backed by valid original receipts. Missing
settlements block that member's replay and further consumer attribution before
new history is retained. Newly admitted trades still use the actor's actual team
and current cycle, with the existing native progress/definition checks.

Native Economy owns payments and inventory. Reconciliation never executes a trade,
claims a pending delivery, refunds a payment or edits a player inventory. Only a
delivery that has actually settled through Economy can move WAITING to CONFIRMED
and then APPLIED. Earlier-cycle, changed-definition and unowned-progress checks
remain authoritative; task recovery does not redirect receipts to another team.
The reconcile command's processed count still counts newly read history entries:
zero can accompany restoration from already retained receipts.

## Native acceptance

The opt-in financial runner adds `-Scope offline` and `-Scope newmember`. Both
start with actual native FTB party/trade evidence. After the first contribution,
FTB saves its real team file; that file is retained before the second contribution.
Each restart replaces the task file and selected financial database only after
the fixture identity/world-lock/SQLite-sidecar checks. Player snapshots are restored
for every actor used across cases. The newer immutable consumer journal survives.

The offline case starts with zero connected players. Lower/equal/higher financial
sequences must reject missing settlements without changing task/evidence/money.
A matching ledger must restore the exact absolute total with the original
transaction identities and survive repeated console reconciliation. A real full
inventory creates a pending delivery; Economy settles it before the owner leaves.
Console reconciliation must then confirm/project that original WAITING receipt
while every contributor is offline, without another payment or delivery.

The new-member case records actual invite/join after the original trades and
connects only that noncontributing member after restart. The same financial
backup cases exercise shared validation, recovery, provider-owned trading and
exactly one new admitted contribution after consistent recovery. The original
contributors remain offline during the tested recovery.

Fresh ordinary builds, the separate Interactions suite and a fresh selected
XMod/Rhino/FTB/Economy fixture provide regression coverage. The XMod init script
accepts an optional fixture path under task artifacts so unrelated old audit
worlds do not enter a global reconciliation run. Exact counts, source/provider/
package/log/native-file hashes and read-only database integrity are recorded in
[the validation manifest](commerce-offline-reconciliation-validation-2026-10-22.json).

## Remaining acceptance

This recovers receipts already retained as WAITING/CONFIRMED/APPLIED. A PREPARED
request with no retained operation UUID still needs its committed provider history
to be read; the current history API requires that actor online. Fully offline
discovery of such missing receipts needs a supported provider contract, not a
guessed operation ID or direct database access. Native item/command/monetary reward
admission and monetary PAID backup validation remain separate work. Large-history
throughput, recovery of removed teams,
deletion/rollback of all evidence copies, original migration sources
and physical ordinary/VR/proxy workflows remain open. No new process-kill or
production deployment/restart is claimed; the user's pinned client fixture stays
unchanged for their later test.

The subsequent [dependency recovery extension](commerce-dependent-recovery-2026-10-22.md)
addresses retained receipts loaded before their native prerequisites, including
sequential tasks within one quest. Its separate native acceptance and failure
reporting contract supplement this earlier validation.
