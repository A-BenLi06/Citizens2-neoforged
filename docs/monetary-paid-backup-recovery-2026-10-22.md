# Validate acknowledged monetary credits after financial restores

Recorded 2026-10-22, UTC+08:00. Extends
[native monetary rewards](ftb-monetary-rewards-2026-10-18.md).

On `7d11f3a`, a real native CustomReward produces one provider credit, a retained
PAID claim and an FTB claimed marker. Restoring the stopped earlier Economy
database loses that credit while retaining the newer native and consumer saves.
Reconciliation silently skips PAID and reports success. The actual failed native
assertion, original transaction/key/team/claim identities, snapshot and audit
source remain under `artifacts/paid-before-*`.

Citizens now validates retained PAID records through Economy 0.5.8/API revision 5
`inspectCredit`. That supported read-only contract checks the persisted immutable
owner/currency/positive amount/key/source and both original ledger postings.
Citizens also requires the provider transaction UUID to equal its original PAID
acknowledgment. A matching balance, unrelated transactions, or a new transaction
under the same original request cannot substitute for that acknowledged identity.
No validation path calls credit or edits consumer evidence to hide a mismatch.

Console reconciliation and startup recovery inspect every PAID claim before any
ACCEPTED payment can settle. A new claimable bound reward additionally checks
prior PAID claims for its native team and quest before preparing a new intent or
calling native marking/reset. Accepted administrator review validates PAID before
changing the reviewed record. Existing native locked/blocked/already-claimed
no-ops keep their no-payment path. Unowned rewards retain native behavior.

Inspection uses the immutable historical beneficiary, even when that player is
offline or has changed parties. Historical checks use the original currency ID
and request rather than applying current frozen-account/currency policy again.
New ACCEPTED credits retain their existing current currency/scale/account checks,
immutable beneficiary and idempotent posting path. A missing provider or API below
revision 5 holds PAID verification explicitly; a missing method is never treated
as missing credit or successful validation. Journals require no schema change.

An ACCEPTED entitlement can legitimately need its first credit, including after
a provider commit lost the consumer acknowledgment. Its established idempotent
settlement remains available after retained PAID evidence passes. A PAID claim
already acknowledges a particular original credit; automatic reconciliation
does not manufacture a replacement transaction after a conflicting financial
restore. Restore a consistent complete ledger/journal/native backup or perform
an explicit reviewed data migration. Do not delete immutable PAID evidence or
use another payment to conceal missing history.

The native stopped-backup fixture retains an actual PAID reward, another native
ACCEPTED-but-unpaid reward and an unclaimed reward in the first quest. Missing
original credit at lower/equal/higher transaction counts must hold startup
settlement, explicit reconciliation and new native admission without changing
money, evidence or native markers. Matching snapshots settle the pending original
entitlement exactly once and permit the later reward. Evidence and final
regression results are in
[the validation manifest](monetary-paid-backup-validation-2026-10-22.json).

Final validation passes 46 native monetary restore checks and 277 commerce graph
checks across fresh seed, lower/equal/higher transaction-count and consistent
snapshots. Negative phases additionally post the identical original request
through the actual provider: its new transaction UUID still rejects reconciliation
and new admission. API revision 4 and absent-provider controls preserve money and
consumer evidence. Matching backups settle the pending seven-unit entitlement
once, then allow the later 25-unit reward.

Both ordinary NeoForge 21.1.248/21.1.233 builds pass 775 tests; separate Interactions
passes 100. Selected XMod/commerce/party regression passes 91 checks, including
actual frozen-account failure and subsequent original-operation recovery. Six
ordinary archives preserve separate ownership and exclude audits/provider classes;
packaged sources and final 248 class bytes match the native production build.
Nine stopped databases pass read-only integrity and original/replacement identity
checks. Build outputs and project caches are isolated.

Draft diagnostics remain retained: expanding a seed schema while an earlier draft
was running left its later phase without the new field; fresh matrices use one
consistent fixture source. The older selected audit expected provider absence to
accept another reward despite prior PAID evidence. Its revised control checks
rejection first, then uses actual account freezing to hold a new credit while
historical inspection remains available. An intermediate proxy control correctly
failed the provider's active-publication check and is excluded from final acceptance.

The provider change is committed separately as `80bca9f`, with 95 ordinary tests
and 62 native checks. It exposes the query; Citizens owns the admission/recovery
policy described here. No production deployment/restart, physical client result
or new process-kill result is claimed.

Full-stack acceptance remains unfinished: offline discovery of unretained
PREPARED requests, removed native teams, arbitrary callbacks, retention/storage/
load/large-history throughput, original migration mappings/items and physical
ordinary/VR/proxy workflows remain open. The user's BenLi06 fixture retains its
pinned files for their later test. Read-only observation after this validation
finds it stopped following its 18:00 shutdown; restart uses the existing guarded
client-acceptance script and does not require recreating its NPCs.
