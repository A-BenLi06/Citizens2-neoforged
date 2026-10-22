# Index retained delivery ownership during admission

Recorded 2026-10-22, UTC+08:00. Extends
[storage/load acceptance](delivery-storage-acceptance-2026-10-22.md) and
[checkpoint-backed recovery](delivery-checkpoint-history-2026-10-22.md).

Delivery preflight/execution previously copied the retained entry list twice and
scanned it for pending ownership and the latest team/task. Actor lookup and
preparation also scanned full history. The real-file probe measured latest-actor
lookup at roughly 725 microseconds with 16,384 retained deliveries; snapshot
allocation reached 131,128 bytes per call. Admission does not need an
administrator's complete immutable history snapshot to identify those owners.

The journal now derives latest-actor and latest-team/task maps plus ordered
unresolved entries for each actor and team/task. Admission and preparation use
those indexes. Actor/task latest lookup uses hash maps; the unresolved query
looks only at the relevant actor/task buckets and returns the earliest sequence
from either. Its cost depends on those unresolved buckets, rather than all
retained deliveries. Administrative entries() still returns the complete
immutable snapshot with its original ordering and proportional copy cost.

Historical review is significant: a previously confirmed old delivery can enter
REVIEW after later deliveries exist. Several reviews can therefore own the same
actor/task at once. Each unresolved record retains its own sequence; resolving
one removes only that fence. Updating an older row never replaces a later
actor/task record. Changing the latest row updates the indexed immutable entry
so its CONFIRMED/REVIEW/RESOLVED state stays current. Ownership keys remain actor
UUID or original team UUID/task ID, independent of current quest/cycle/definition.

Indexes publish only after the immutable revision is forced and moved to its
final path. An installation failure retires the writer through the existing
failed-write path. Startup reconstructs indexes from the original validated
records; a failed history load never publishes a service. Every schema, revision,
sequence, actor-chain, native outcome and checkpoint check remains required.
Nothing prunes history or persists a separate index/backup domain.

Two additional tests compare each indexed result against an independent scan of
all retained entries after prepare, confirmation, old-row review, out-of-order
resolution and multiple reopen cycles. Cases retain several reviews per actor
and shared task, preserve an unrelated pending scope while valid work proceeds,
separate teams and rebuild missing-outcome REVIEW fences during startup. Existing
real filesystem failures and damaged-history restoration tests remain included.

The repeated real-file workload retains 1,024/4,096/16,384 deliveries with 128
actors, validates each predecessor and all reopened actor heads, and preserves
all 43,008 immutable revision files and original admission bytes. Observed means:

| Retained deliveries | Earlier actor lookup | Indexed actor lookup | Indexed admission reads | Admission allocation/call |
|---:|---:|---:|---:|---:|
| 1,024 | 43.05 us | 0.60 us | 1.78 us | 80 bytes |
| 4,096 | 142.68 us | 0.31 us | 0.24 us | 48 bytes |
| 16,384 | 724.71 us | 0.32 us | 0.11 us | 48 bytes |

Admission reads query unresolved ownership, latest actor and latest team/task;
they exclude player checkpoint parsing and provider/native state reads. Means
cover 10,000 calls following 1,000 warmups. JIT compilation continues during
these short loops, so the combined later loop can be faster than the earlier
single-lookup loop. These shared-workstation measurements describe the observed
lookup/allocation change, not stable production tick guarantees or a benchmark
threshold. Full history loading and administrator snapshots remain proportional to
retained history; every new revision still requires its own forced write. The indexed run's 16,384-entry append/reopen took
441.72/25.42 seconds under concurrent validation, versus 179.77/11.12 seconds in
the earlier run; no disk or startup throughput improvement is claimed.

Final validation passes 782 ordinary tests on each NeoForge target and 100
Interactions tests, without failures/errors/skips. Native acceptance passes
callback61, normal17, history45, storage17, selected PREPARED/XMod/commerce/party104
and six actual JVM kills with boundary18/recovery40: 302 checks. Every launched
final job reached successful terminal completion. One earlier invocation rejected
an unsupported audit mode before mutation cases ran; the corrected all-cases run
uses a fresh fixture, with the original diagnostic retained.

Six ordinary source/binary archives exclude audit classes/mixins and foreign
implementations, retain separate mod ownership and match the final production
source and native/load/XMod class bytes, including nested index-key classes.
Eight stopped fixtures cover 12 world/native-journal locks plus three independent
load journals; database ownership and absence of sidecars are verified. Economy
immutable read-only quick_check, balanced postings and exact original prepared
operation/transaction identities pass. Exact source/provider/log/package/native-
save/journal/file-index identities are in
[the validation manifest](delivery-index-validation-2026-10-22.json).

The opt-in load probe accepts storageAuditBuildDir; native history/storage runners
accept artifact-confined BuildDirectory and ProjectCache parameters. This keeps
new output/cache locations independent of historical evidence while retaining
default commands. Both runners reject an outside-artifact build location before
creating a fixture or launching Gradle. Run delivery fixtures sharing port25641
sequentially. Production and the deferred ordinary-client fixture are unchanged.

Retention/device-loss/provider failure, broader recovery, original migration
provenance and physical ordinary/VR/proxy acceptance retain their separate gates.
