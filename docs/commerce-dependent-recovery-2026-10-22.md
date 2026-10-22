# Recover commerce projections across native task dependencies

Recorded 2026-10-22, UTC+08:00. Extends
[offline reconciliation](commerce-offline-reconciliation-2026-10-22.md).

## Failure and recovery behavior

Retained commerce receipts reload in receipt-ID order, which need not match FTB's
prerequisite order. With an older native team file, a valid dependent receipt can
therefore be projected before its prerequisite. On `1cba2e7`, administrator
reconciliation throws at that first native availability rejection and never
restores the prerequisite. The actual stopped-file reproduction is retained in
`artifacts/dependency-commerce-before-2026-10-22-consistent.log`.

Online replay and administrator reconciliation now attempt every eligible original
binding/team/cycle. A successful projection is removed from the batch; remaining
projections are retried only if at least one scope succeeded. This lets FTB unlock
dependent quests or sequential tasks before they are retried. Each successful
iteration removes at least one scope, and a pass with no success terminates.
FTB's native availability, definition, cycle and absolute-progress checks still
decide whether a projection is valid. No task order or dependency graph is guessed.

Retained financial settlements are validated before any batch projection. A
missing settlement rejects reconciliation before any native task changes. A task
definition or other projection failure can coexist with recovery of valid scopes:
administrator reconciliation attempts those scopes and then reports the remaining
task/team/cycle with the original cause. Additional failures are suppressed on
the first exception. Regular replay logs each remaining scope. Original evidence
is retained so a later repair can resume only the remaining projections.

Recovery does not execute trades, claim inventory deliveries, issue money or
redirect beneficiaries. Its processed count continues to count newly read provider
history entries, so zero can accompany restoration from retained receipts.

## Native acceptance

The opt-in financial runner supports `-Scope dependency` for separate quests and
`-Scope sequential` for two ordered tasks in one quest. Each fixture creates
native tasks, selects request keys whose actual receipt IDs reload in reverse
dependency order, executes two real paid/delivered trades, and saves the actual
FTB team file before either trade. Each restarted case has zero contributors
online and restores that older native file with the selected financial snapshot.
Fixture identity, exclusive world lock and absent SQLite sidecars are checked
before replacement.

Lower/equal/higher financial sequence cases must reject the missing dependent
settlement without projecting even the valid prerequisite. A matching ledger
must recover both tasks in one call despite reverse receipt order, preserve the
original operations/transactions and survive repeated reconciliation without
another payment. The separate-quest case also restores the old progress again,
invalidates the dependent definition, checks valid prerequisite recovery plus an
explicit contextual failure, then repairs the definition and resumes recovery.

Exact results, source/provider/package/log hashes, native file identities and
read-only SQLite integrity checks are recorded in
[the validation manifest](commerce-dependent-recovery-validation-2026-10-22.json).

| Fresh validation | Result |
|---|---|
| Separate native quest dependencies | 35 assertions across five stopped-backup stages |
| Native sequential tasks in one quest | 33 assertions across five stopped-backup stages |
| Selected XMod / commerce / party rewards | 44 / 27 / 19 checks |
| Ordinary NeoForge 21.1.248 and 21.1.233 | 775 tests each; no failures, errors or skips |
| Separate Interactions suite | 100 tests; no failures, errors or skips |
| Ordinary release archives | Six inspected; final source matches, no audit/provider classes |
| Stopped SQLite databases/snapshots | Six read-only `quick_check` results are `ok` |

## Remaining boundaries

This covers retained receipts and native prerequisite/sequential availability.
Fully offline discovery of PREPARED requests without operation UUIDs, native
item/command/monetary reward admission after inconsistent backups, monetary PAID
inspection, removed teams, callback mutation and large-history throughput remain
separate work. Original content provenance and physical ordinary/VR/proxy
acceptance remain open. No new process-kill or production activation is claimed.
