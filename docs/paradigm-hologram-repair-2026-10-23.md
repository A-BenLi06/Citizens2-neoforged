# Private Windows hologram reader repair

Native runs completed 2026-10-23; verification recorded 2026-10-24, UTC+08:00.
The private owner candidate closes Paradigm's
configuration reader before persistence replaces `holograms.json`. The original
unmodified 2.5.0 provider still has the [documented defect](paradigm-windows-hologram-loading-2026-10-23.md).
This local repair does not change Citizens production code or establish a
distributable provider release.

The matching upstream owner source is commit
`2beb0750b6b63d078dde2d71f60500e63bc991d7`. All baseline method disassembly matches
the installed 2.5.0 implementation. The previously reviewed 2.5.2 source adds a
numeric dimension-ID conversion absent from that installed release; the initial
comparison is preserved and excluded from the repair. No unrelated behavior from
2.5.2 is imported.

The build retains all field/method descriptors and the original Java 17 class
target. Only `HologramStore.load()` changes, and only its containing `.class`
entry differs in the complete provider archive. Original archive ordering and
all other entry contents remain exact. The private candidate manifest pins
source, dependencies, disassembly, classes and complete JAR hashes. Private source
history is retained in `artifacts/paradigm-hologram-fix-source` with user-authored
commits. Modified provider source/binary remains in ignored artifacts.

The same standalone audit bytecode fails the original implementation at
`existing_valid_file_stays_enabled` and passes 38 checks with the private repair.
Coverage includes fresh/existing/same-store reload, rich definitions, settings,
literals, Unicode, defensive copies, edits and duplicates, intentionally disabled
configuration, malformed JSON/UTF-8, invalid dimensions and normalized IDs,
line limits, null/default normalization and restoration. Invalid inputs retain
their exact recoverable bytes. A genuinely external open reader still causes
safe disablement; releasing that lock permits recovery. The fix does not suppress
failed writes or remove the existing safety behavior.

The native comparison uses fresh isolated copies of the accepted integrated
logging-recover world and configuration, 64 top-level provider archives and the
same extra single probe class in Citizens. All inherited production/archive
entries and the 29 earlier probes remain exact. The original and fixed fixtures
run sequentially at `127.0.0.1:25642`; accepted earlier fixtures and the deferred
ordinary-client fixture remain stopped and unchanged.

The private candidate passes 82 accepted checks across standalone storage and
two actual native server processes:

| Acceptance | Checks | Result |
|---|---:|---|
| Standalone owner storage and corruption safety | 38 | Passed |
| Full-pack entity rendering and live reload | 22 | Passed |
| Native restart, rendering and live reload | 22 | Passed |

The original full-pack process reproduces the exact disabled-owner assertion and
Windows `AccessDeniedException`, then exits zero after saving. It is excluded
from functional acceptance counts. Both fixed processes also exit zero and
release world/H2 locks. Actual runtime inventory resolves the same 121 provider
IDs/versions in all three processes; the private Paradigm hash distinguishes the
repaired candidate, whose descriptor version remains 2.5.0.

The native probe observes real provider-owned text-display entities, exact static
lines including `$`, a backslash and Unicode, loaded-chunk runtime status,
unchanged entity UUIDs across live reload and coexistence with a Citizens hologram
NPC. Restart recovers the same complete configuration, definition fields and line
order. Saved configuration bytes remain exact across the fixed processes.

The [independent manifest](paradigm-hologram-repair-validation-2026-10-23.json)
re-reads complete SQLite contents and checks their captured hashes, integrity,
balanced ledger/projections and exact original financial/claim/history records.
Only expected currency/system-account startup timestamps and additional currency
synchronization audit events change. All 294 inherited Citizens journal,
FTB-team and player files remain byte-identical in each process; these include
all originals previously accepted in the combined run.

Stopped entity-region NBT contains no owned display lines in any of the three
worlds, corroborating shutdown cleanup independently of runtime assertions.
The verifier checks 7,710 prior protected-file records, including the earlier
accepted/failed fixtures and deferred client pins. Three completed-phase launch
attempts reject before Java and preserve all 13,897 protected files. Eight final
executable references are frozen separately. Accepted gameplay was not replayed
to repair a recorder.

The initial builder's ZIP reread failure is retained: Python mutates a reused
`ZipInfo` object's header offset during writing. A fresh build copies metadata
objects, then verifies every packaged entry. The independent seed verifier's
initial LF-only reconstruction of Windows `server.properties` is also preserved;
the corrected verifier reconstructs the actual platform line endings. Neither
recording correction reruns accepted gameplay.

Restart preflight initially rejected vanilla's rewritten properties date comment
before launching Java. The corrected preflight permits comment-only changes,
requires all settings lines to remain exact and records current launch/runtime
hashes. The failed preflight and original launcher are retained; the accepted
seed process was not replayed.

Disk preflight briefly showed only about 130 MB free on C:. A task-owned Gradle
cache copy to E: verified all 8,005 files (3,400,064,366 bytes). Available C: space
subsequently recovered to about 92 GB, so no original cache was moved or removed.
Automatic approval review rejected deleting the temporary E: copy with “blocked
by policy”; that copy is retained at
`E:/citizens2-neoforged-task-cache-20261023/gradle-contained-caches`. The copy
manifest and rejection record are preserved separately from acceptance evidence.

Full logs retain unrelated recipe/tag/mixin warnings, slow WorldEdit/CopyFoxies
block-state enumeration and profiler shutdown messages. No clean-log or sustained
performance claim is made. See the [audit workflow](../tools/paradigm-hologram-runtime-audit/README.md).

The private repair is not distributed or activated in production or the deferred
physical-client fixture. Paradigm's supported inbound evaluation API, original
quest mapping and missing item definitions, broader crash/backup/load/retention,
physical ordinary/VR/proxy/camera acceptance and the production release/rollback
decision remain open. The user's ordinary-client test remains explicitly deferred.
