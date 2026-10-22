# Native FTB monetary rewards and durable recovery

Update 2026-10-22, UTC+08:00: [exact journal decoding](journal-record-decoding-2026-10-22.md)
rejects fractional/overflowed amounts, coerced field types, unsupported enum names
and duplicate members before loading retained bindings or claims. Valid original
histories reopen unchanged; this does not create a replacement payment or repair.

Update 2026-10-22, UTC+08:00: [PAID financial-restore validation](monetary-paid-backup-recovery-2026-10-22.md)
checks the original public provider credit and acknowledged transaction UUID before
startup/reconciliation settles pending claims and before new claims in the same
native team quest. Economy 0.5.8/API revision 5 supplies read-only inspection.
Historical PAID records are never automatically recredited to hide missing history.

Implemented 2026-10-18, UTC+08:00. Minecraft 1.21.1, FTB Quests 2101.1.36,
Economy 0.5.5 (`605e187`), deployed NeoForge target 21.1.248. The optional
Citizens mixin observes the selected provider's **public player claim method**
at its call to `TeamData.markRewardAsClaimed`. It does not edit private fields,
replace FTB events or implement another ledger. Interactions remains a separate JAR.

An authenticated operator binds an existing native CustomReward to a configured
currency and exact major-unit amount:

```text
/citizens questreward bind <reward ID> <currency ID> <amount>
/citizens questreward list
/citizens questreward review
/citizens questreward reconcile
/citizens questreward resolve <claim ID> <accept|reject> <reason>
```

Currency ID and precision come from the active Economy public API; conversion
rejects rounding, overflow and nonpositive amounts. Binding and review require
the exact online operator session with `citizens.admin`. Console reconciliation
and listing are allowed with that authority. A binding is immutable: identical
requests reuse it; changing the reward, currency or amount requires a separately
reviewed content migration. No shipped quest, currency or recipe IDs are imposed.

FTB owns availability, completed tasks, locking, reward blocking, per-player/team
claim status and repeat reset. Citizens additionally requires current actor/team
identity, current native reward instance and visible quest/chapter. Unbound
rewards retain native behavior. If the journal cannot open at server startup,
CustomReward claims fail closed because binding ownership cannot be proven;
ordinary native reward types remain available.

For a bound claim, Citizens durably writes PREPARED **before** native marking,
then durably writes ACCEPTED with its observed post-mark cycle only after the
native boolean accepts. These boundaries occur before CustomReward/XMod callbacks.
The record includes binding epoch, native team, quest/reward, pre-reset cycle,
native owner and immutable actual claimant/beneficiary. A team reward pays the
first accepted claimant once for its native team generation; it does not split
funds or manufacture a financial team account. A player reward pays its native
claimant. Later party membership does not redirect a pending entitlement.

Economy credit uses the same persisted payload and operation key on every retry.
The key includes binding epoch, team, quest/reward, owner, cycle and beneficiary;
the returned transaction UUID becomes PAID. Missing/retired providers or account
failures leave ACCEPTED pending. Startup and operator reconciliation reuse the
public idempotent credit call. A provider commit followed by a missing consumer
acknowledgement replays the original transaction rather than issuing new money.
Currency removal/precision changes hold the payment for review. Accepted historic
entitlements are not reinterpreted by later quest redefinitions.

Native claim/reset and its callbacks remain FTB behavior. Callback failure after
payment cannot undo the recorded credit or make a retry pay again. Other effects
inside arbitrary scripts are not made durable by this bridge. Do not configure
a second script credit on the same bound reward. Native direct administrative
marking without a player claim and automated block-entity reward paths are not
monetary entry points supplied by this adapter.

Journal files live in `<world>/citizens/quest-rewards`, with single-writer locking,
forced immutable JSON revision files and atomic publication. Incomplete writes,
invalid schemas, missing revisions, changed payloads and ownership conflicts fail
closed. PREPARED recovered after process death becomes REVIEW; Citizens cannot
infer whether FTB accepted from reset progress or claim timestamps. Authenticated
review preserves the original beneficiary and operation key. Rejected claims
remain retained and cannot silently acquire a new intent in the same generation.
Lower native repeat counts and reused monetary generations reject new admission.

## Validation and practical limits

The actual XMod/Rhino/FTB/Economy fixture exercises native decoded claims, exact
precision, incomplete and duplicate claims, repeat reset, provider absence,
actual committed receipt replay, current party changes, definition/ownership
changes, native rollback and a throwing CustomReward callback. Journal unit tests
exercise missing native outcome, retained reviews, individual/team ownership,
history corruption, file locking and owning-thread boundaries. The absent-Economy
fixture rejects binding before journal/native effects while unbound FTB rewards
continue to reset craft tasks normally.

Final evidence: 44 actual selected-stack checks on 21.1.248; 5 actual process
restart checks after a forced JVM kill (plus the pre-kill acceptance assertion);
140 absent-Economy/XMod regression checks; 754 ordinary tests on the 21.1.233
baseline, with zero failures/errors/skips. Standard binary/source JAR inspection
excludes probes, optional provider classes and cross-mod packaging; the separate
Interactions binary hash remains unchanged. The XMod fixture refreshes FTB's
public caches after constructing definitions so a transient empty quest cannot
retain cached completion/visibility across repeated fixture runs. Its monetary
objects use native generated IDs to keep historic immutable bindings interpretable.

`tools/run-reward-crash-audit.ps1 -JavaHome <JDK 21>` runs the isolated loopback
fixture. After an actual native accepted claim, it commits through real Economy
without consumer acknowledgement and kills the exact opt-in JVM identified by
its runtime PID marker. No shutdown save runs. The next process replays that
transaction into PAID and retains exactly one credit. Native FTB loads the older
pre-claim saved cycle; another claim is fenced before payment. Fixture data and
provider sources are isolated; production is untouched. Logs and artifact hashes
are retained in `artifacts/ftb-monetary-validation-2026-10-18.json`.

This validates the committed-ledger/lost-acknowledgement boundary, not every kill
point. Kill during/before native acceptance requires review; kill during an
immutable journal write retains incomplete evidence and requires file recovery.
Filesystem force/atomic move does not establish power-loss durability on every
Windows storage device. Preserve the **entire reward journal and Economy ledger**
outside world block/FTB rollback operations and coordinate their backups. Rolling
back or deleting the monetary journal itself destroys its beneficiary/fencing
evidence; arbitrary inconsistent financial backup restore is not accepted here.
No world rollback silently compensates ledger money. Full kill-boundary matrix,
party-member sharing/reconnect permutations, physical clients, provider/content
reload and combined migration acceptance remain open under ADAPT-04/05/09/11.

The complete selected fixture cannot load on Citizens' ordinary 21.1.233 baseline:
selected JEI requires 21.1.238 and Economy requires 21.1.247. No provider metadata
is changed to bypass these floors. Ordinary Citizens builds remain 21.1.233.
