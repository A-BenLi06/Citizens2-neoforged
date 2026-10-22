# Delivery journal storage and retained-history acceptance

Recorded 2026-10-22, UTC+08:00. Extends
[checkpoint backing](delivery-checkpoint-history-2026-10-22.md) without changing
production code or the journal schema.

Five new real-filesystem tests exercise failed immutable confirmation, review and
resolution publication, loss of a whole middle delivery, truncated/unsupported
records, changed actor/admission payloads and exact restoration. An existing path
at the next immutable revision produces a real IOException. The active writer
then refuses further operations even after the conflict is removed. Original
revisions remain unchanged; reopening preserves the last retained outcome or
converts a genuinely missing outcome to REVIEW. Failed loads release ownership
without rewriting corrupt bytes, and restoring the exact original files permits
loading again. The five tests and eight existing journal tests pass on both
NeoForge 21.1.248 and 21.1.233: 13 per target, no failures/errors/skips.

The opt-in native fixture creates the publication collision only after the actual
ItemTask consumes three paper and advances progress to three. Confirmation fails,
no player checkpoint is invented, and removing the collision does not revive the
failed writer. Remaining two paper and progress survive both rejected retries and
a clean restart. Restart retains the original PREPARED admission as REVIEW, with
no fabricated outcome. Authenticated inspection/resolution preserves the native
partial effects, then only the remaining two paper are consumed on the original
actor/team chain. A completed retry creates no new intent. The two phases pass
8 and 9 checks. The empty conflicting directory is retained outside the journal;
all original immutable revisions and saved player/team files remain inspectable.

A separate journal API probe appends and forces actual immutable files with 128
actors, validates every original predecessor, closes/reopens each journal, checks
all latest actor identities and confirms the first admission bytes are unchanged.
It retains all 43,008 revision files across three fresh journals:

| Deliveries | Immutable JSON files | Append/force | Warm reopen | Latest-actor lookup mean | Snapshot allocation/call |
|---:|---:|---:|---:|---:|---:|
| 1,024 | 2,048 | 13.98 s | 1.52 s | 43.1 us | 8,248 bytes |
| 4,096 | 8,192 | 44.15 s | 4.08 s | 142.7 us | 32,824 bytes |
| 16,384 | 32,768 | 179.77 s | 11.12 s | 724.7 us | 131,128 bytes |

Lookup means cover 10,000 calls after 1,000 warmups; snapshot means/allocation cover
1,000 calls. Reopen uses a warm filesystem cache after appending. Allocation is
cumulative on the calling thread, not retained heap. This local Java21, 2GiB-heap
workload establishes bounded retained-history correctness and records linear
lookup/snapshot costs. It establishes no production tick rate, cold-device
latency or capacity limit. It adds no pruning, guessed retention duration or
relaxed history validation. Larger histories and admission costs remain explicit
performance work.

Exact source/log/provider/journal/native-save identities and the hash index for
all retained load files are in [the validation manifest](delivery-storage-validation-2026-10-22.json).
The production tree and six previously verified release archives match the
[285-native/775-per-target/100-Interactions baseline](delivery-checkpoint-history-validation-2026-10-22.json).
The new native and load probes compile those same production class bytes. Audit
classes remain in their opt-in outputs and are excluded from the release jars.

Reproduce sequentially from the repository root with PowerShell7 and Java21:

```powershell
./tools/run-delivery-storage-audit.ps1 -JavaHome $env:JAVA_HOME -LogPrefix storage-native
./neoforge/gradlew.bat -p neoforge --project-cache-dir ../artifacts/delivery-storage-load-cache -I ../tools/delivery-storage-audit.gradle '-Pneo_version=21.1.248' runDeliveryStorageAudit --console=plain
```

The native runner needs the existing isolated provider template, creates a fresh
loopback fixture, verifies its run identity and checks exclusive stopped-world
ownership between phases. The load probe creates fresh directories and preserves
its result and records. Build outputs/caches are isolated. Native delivery audits
sharing port25641 or an output directory must run sequentially.

This covers publication collisions, not full-device failures, power loss during
force/move, timed retention, other journal/logger owners or arbitrary production
load. Original migration provenance and deferred ordinary/VR/proxy acceptance
remain open. The BenLi06 fixture stays pinned and stopped; production is unchanged.
