# Retained commerce receipts after financial backup restore

Recorded 2026-10-22, UTC+08:00. This adds a bounded recovery check to
[native commerce tasks](ftb-commerce-tasks-2026-10-18.md); it does not establish
complete financial backup or migrated-stack acceptance.

## Defect and behavior

A restored Economy ledger can retain its journal UUID and regain the same or a
higher global sequence through unrelated trades while losing an original admitted
settlement. Previously, APPLIED task evidence with already matching FTB progress
could return early without checking that settlement. Explicit reconciliation could
therefore report success against an inconsistent financial backup.

Before replay, new attribution or administrator resolution, Citizens now rechecks
the current online actor's retained WAITING, CONFIRMED and APPLIED operations using
Commerce capability 3's public operation lookup. Immutable receipt fields must
match. CONFIRMED/APPLIED must still satisfy their binding's settlement/delivery
policy; WAITING can remain unsettled. Existing provider UUID/high-water checks
also run before these operations. Missing or changed settlements stop consumer
admission and reconciliation without rewriting original evidence.

Economy still owns actual trades. An observation failure does not cancel, refund
or compensate a native trade. Citizens does not remove already saved FTB progress,
items, money or claimed rewards. An administrator must compare retained evidence
with the original provider receipts and restore consistent stopped backups before
retrying. Catching the sequence up with unrelated trades is insufficient.

## Native validation

The opt-in `tools/run-financial-backup-audit.ps1` creates a fresh loopback fixture
at port 25646, unique actor/shop/task identities and isolated Gradle output/cache.
It copies pinned Economy 0.5.6 and FTB dependencies. It never targets production.
SQLite `VACUUM INTO` captures native ledger snapshots before and after an actual
paid/delivered trade. The trade credits two bindings: one complete and one still
incomplete. The runner replaces the selected financial database and original player
file only after clean shutdown, fixture identity and exclusive world-lock checks;
WAL/SHM sidecars cause refusal.

Cases cover a regressed sequence, equal sequence after one unrelated trade, higher
sequence after two unrelated trades, and restoration of the matching original
ledger. Rejections preserve original APPLIED evidence, native progress, inventory
and balance. Further native trades still execute, but receive no new consumer
attribution or incomplete-task credit. Matching restoration retains the exact
original operation/transaction and both projections without another payment or
delivery. Each database must pass the provider's balanced-ledger audit.

The before-fix reproduction and final results, exact source/provider/archive hashes,
logs and read-only SQLite integrity results are recorded in
[the validation manifest](commerce-financial-backup-validation-2026-10-22.json).
These are stopped-restore cases; no new process-kill or power-loss claim is made.
The existing 90 selected native checks, ordinary tests on both NeoForge targets
and the separate Interactions suite provide regression coverage.

## Remaining boundary

The subsequent [shared/offline extension](commerce-shared-backup-recovery-2026-10-22.md)
adds a supported provider inspection contract and contributor-wide checks within
the relevant task/team/cycle. The following actor-only limitation describes this
earlier acceptance boundary; the linked extension records its additional coverage.

Validation is actor-scoped. Commerce capability 3 requires an online actor even
for read-only operation lookup and journal position. Shared task admission may
depend on another, offline member's previously admitted settlements. A supported
provider-owned read-only inspection contract is needed to verify that boundary
without guessing private storage or requiring every historical member online.

Monetary CustomReward PAID evidence and native reward admission are separate;
the current monetary API has no read-only settlement-receipt lookup. This change
does not fence all reward claims after inconsistent backups. Large retained
histories need throughput measurement before production acceptance: actor receipt
validation scans retained entries on replay and new attribution. Rolling back or
deleting every copy of both evidence and ledger cannot be detected here. Original
content provenance and physical ordinary/VR/proxy workflows remain open. The user
will test the separately pinned ordinary-client fixture later.
