# Index retained craft evidence during gameplay

Recorded 2026-10-23, UTC+08:00. Advances craft storage/load acceptance while retaining
the [journal contract](quest-evidence-journal-2026-10-18.md) and
[independent recovery](craft-recovery-isolation-2026-10-22.md).

Craft admission previously copied and sorted every retained entry to check replay
identity, then scanned history again for applied totals and REVIEW fences. Outcome
handling repeated that snapshot to find its observation. Projection scanned
generations and history again to find its target, totals and confirmed work.
These server-thread reads grew with unrelated, already-applied historical crafts.

The journal now derives maps for the latest generation per binding/team/quest
scope, the earliest observation/task admission across generations, each
observation's current entries, and each generation/task's totals and outstanding
CONFIRMED/REVIEW records. Gameplay uses these lookups. Outcome handling copies
and sorts only the relevant observation's entries; projection snapshots only its
task's confirmed work. Administrative history snapshots and reconciliation's
initial discovery retain their existing ordering and proportional cost.
The derived maps add memory proportional to retained entries and scopes; this
change does not bound total journal memory or disk usage.

Replay still selects the earliest admission by createdAt, then entry ID, across
generations. Revising an old entry refreshes its state without replacing an
earlier owner. Multiple REVIEW records retain independent fences. A lower native
cycle rejects whenever a later cycle remains retained. Teams, epochs, quests and
tasks remain separate identities.

No persisted index or schema is added. Startup validates all immutable histories
and companion bindings before rebuilding indexes or publishing missing-outcome
recovery. Indexes update only after each revision is forced and atomically moved
to its immutable path; a failed write retires the journal. Totals use BigInteger
internally and longValueExact when queried. Confirming/applying an overflowing
history remains allowed, while querying its total still throws before and after
reopen, preserving the original query-time checked-overflow behavior.

The real-file probe first compiled the original 6dc7eb6 journal and admitted
5,376 crafts across three workloads, retaining 15,456 immutable files. The indexed
implementation reopened those exact directories. Every file's SHA-256 and the
complete file set remain equal. Each generation contains eight crafts: six
APPLIED, one CONFIRMED and one REVIEW. The combined read sequence checks absent
replay ownership, an existing observation, generation identity, REVIEW fencing,
confirmed work and confirmed/applied totals.

| Retained crafts | Earlier reads | Indexed reads | Earlier allocation/call | Indexed allocation/call |
|---:|---:|---:|---:|---:|
| 256 | 378.74 us | 82.04 us | 18,012 bytes | 10,100 bytes |
| 1,024 | 1,628.13 us | 36.58 us | 53,808 bytes | 9,577 bytes |
| 4,096 | 8,044.72 us | 21.16 us | 205,613 bytes | 9,576 bytes |

Means cover 2,000 calls after 200 warmups. Indexed reflection dispatch, assertions
and canonical ID conversion are included. JIT and concurrent workstation work
affect these short loops; decreasing indexed means are not a throughput scaling
guarantee. Native task lookup, callbacks and disk writes are outside this read
sequence. Startup still validates every revision and every new revision still
requires its own forced write. No startup, disk or production tick guarantee is
claimed.

Fresh ordinary validation passes 828 tests on each target, without failures,
errors or skips. New coverage compares scoped reads with independent full-history
scans through admission, returned/interrupted outcomes, accept/reject review,
application, multiple tasks/teams/generations and repeated reopen. Equal timestamp
ID tie-breaking preserves replay ownership; failed revision installation retires
every indexed read. Overflow remains query-time. The first timestamp fixture
omitted required null fields during rewriting and was correctly rejected; the
corrected fixture preserves the complete schema. Both failed-run logs/reports
are retained.

Native recovery passes seven seed and eleven restart checks on each NeoForge
21.1.248/21.1.233. Three actual result takes retain their original observations,
actor, quantities and recipe components while two native definitions are invalid.
Offline reconciliation recovers the independent valid task, then each repaired
task, without recrafting or changing inventory. Stopped native team files retain
all three totals at one. A fresh 21.1.248 Create regression passes 263 checks,
bringing native acceptance to 299. All 100 Interactions acceptance tests run
freshly; the preceding 42-test parity-only selection is recorded separately.

The first Create setup copied a recipe already changed by an earlier completed
audit, so its initial-recipe assertion rejected before the machine checks. That
world/log remains retained. The corrected fresh fixture uses the existing
prepare-create-audit.ps1 recipe and preserves its initial script before launch.

All final jobs reach terminal success. Six ordinary archives exclude audits and
foreign provider implementations, preserve separate mod ownership, and match
production class bytes exercised by their corresponding native builds. The Create
build matches the selected ordinary package too. Output/cache directories remain
separate under artifacts, preserving prior fixtures and archives. Exclusive
world/journal ownership checks confirm stopped fixtures, including the unchanged,
user-deferred BenLi06 ordinary-client fixture.

Exact source, record-image, test, provider, native-save and package identities are
in the [validation manifest](craft-index-validation-2026-10-23.json). The opt-in
[runner](../tools/run-craft-storage-audit.ps1) takes JavaHome, GsonJar, Mode
(scan/indexed), Fixture and BuildDirectory. Outputs must stay under artifacts;
scan needs a fresh fixture, indexed reuses it, and each build directory must be
fresh. Owned source copies and audit classes remain outside ordinary archives.

Retention, device loss, broader dependency/party/machine recovery, original
quest/item/account migration inputs, supported Paradigm inbound evaluation and
deferred ordinary/VR/proxy acceptance remain separate gates. The full goal stays
active. Production has not been activated or restarted.
