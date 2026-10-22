# Recover missed commerce receipts without reconnecting their owner

Recorded 2026-10-22, UTC+08:00. Extends
[retained offline reconciliation](commerce-offline-reconciliation-2026-10-22.md).

On `6c83159`, an actual native trade pays and delivers while a failed commit
notification leaves Citizens with only PREPARED pre-trade attribution. After the
original player disconnects, console reconciliation returns without discovering
the original receipt or projecting its task. The failed assertion, original
owner/team/request/operation/transaction identities and exact audit source remain
under `artifacts/prepared-before-*`.

Economy 0.5.9/Commerce capability 5 now exposes `inspectRequest` for exact original
request discovery without an online owner or a retained operation UUID. The
provider validates the canonical immutable request and native journal metadata,
returns the original receipt and current inventory outcome, and performs no
trade, delivery, payment or recovery repair. Provider commit `4737db6` passes 95
ordinary tests and 72 native checks; its contract is documented separately.

Citizens administrator reconciliation validates retained settlements, replays
available online history, then inspects unresolved PREPARED requests using their
original owner, shop/item/action/quantity/key/origin. It checks the original
journal identity/high-water and exact metadata/outcome before retaining discovered
receipts. Repeated requests shared by multiple bindings use one observation
within that reconciliation. This cache never survives an admission or callback.
All unresolved inspections finish before any discovered receipt is retained.

The existing journal transition keeps the original team/cycle attribution and
original receipt identity. COMMITTED evidence can become CONFIRMED/APPLIED;
pending delivery stays WAITING until Economy actually delivers it. A missing
request stays PREPARED. Missing evidence cannot distinguish an uncommitted attempt
from an inconsistent financial restore, so reconciliation never invents an
operation, payment, delivery or rejection. Existing retained-receipt and financial
rollback fences remain authoritative. An unavailable provider or capability below
5 reports the unresolved inspection requirement instead of claiming recovery.

Reconciliation counts newly retained PREPARED receipts as processed entries in
addition to online history entries. Repeated reconciliation returns zero when it
finds no new receipts; already retained delivery completion can still project
progress with a zero count. Bindings, journal schemas, native definitions and
beneficiaries retain their existing ownership rules. Trading, inventory and
delivery remain owned by Economy.

The opt-in native fixture reproduces missed notification through actual event
dispatch. The owner then joins a different native party and disconnects; recovery
must apply the original operation to the original team. Separate actual trades
exercise a full inventory/pending delivery and a failed commit after pre-trade
attribution. Console recovery keeps missing evidence PREPARED, pending evidence
WAITING, and money unchanged. Actual provider delivery followed by another
disconnect allows offline projection into that later trade's original party.
Absent-provider and capability-4 controls preserve entries and money.

The fresh selected fixture passes all 13 new PREPARED checks plus 45 XMod, 27
commerce and 19 party-reward checks. Both ordinary NeoForge 21.1.248/21.1.233
builds pass 775 tests each; separate Interactions passes 100. Fresh financial
regression passes commerce graph277 and monetary PAID46. A retained, hash-verified
Economy 0.5.6/Commerce capability 3 separately passes all 39 original actor-only
restore checks; unresolved offline request discovery still requires capability 5.
Six release archives exclude probes/foreign classes, preserve separate ownership,
and match final sources/native class bytes. Fourteen stopped databases pass
read-only integrity and exact original/recovered/missing-request identity checks.

Two draft fixture diagnostics are retained. Freezing before native execution
failed during provider preflight, so no PREPARED attribution existed; the final
control freezes through an opt-in LOWEST starting listener after Citizens observes
the native request. The capability-4 stub initially omitted the lifecycle
availability method; final acceptance supplies that method and rejects any request
inspection or payment through the older stub. Neither diagnostic changed the
production reconciliation implementation.

The first legacy invocation failed during fixture setup because its old
`build/libs` filename no longer existed. No server started for that invocation.
The final fresh legacy matrix uses the exact retained 0.5.6 binary from the earlier
validated fixture after checking its SHA-256 and metadata. The diagnostic and final
provider hash are retained in the manifest.

Final results and exact source/provider/package/log/journal/database identities
are recorded in [the validation manifest](commerce-prepared-validation-2026-10-22.json).
No new process-kill, production activation or physical-client result is claimed.

Remaining gates include removed native teams, arbitrary callback mutation,
retention/storage/load/large-history throughput, original migration IDs/items and
physical ordinary/VR/proxy workflows. Unknown legacy source data is not invented;
the deferred BenLi06 fixture retains its pinned files and is currently stopped.
