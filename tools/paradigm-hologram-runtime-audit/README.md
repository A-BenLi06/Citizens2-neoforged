# Private Paradigm owner repair acceptance

This workflow tests the Windows configuration-reader correction in a private
local owner candidate. Modified Paradigm sources and binaries remain in ignored
`artifacts`; this repository supplies build/verification tools and fixture probes.
No production or deferred physical-client fixture is changed.

`build-paradigm-hologram-candidate.py` requires a matching upstream source,
the private fixed source, installed provider, Java home and actual dependencies.
It compares all baseline method disassembly with the installed implementation,
retains field/method descriptors and Java target, limits changed bytecode to
`load()` and preserves every other archive entry. The accepted matching source
is upstream commit `2beb0750b6b63d078dde2d71f60500e63bc991d7`; the reviewed
2.5.2 source contains an unrelated dimension-ID change and is not the baseline.
The build manifest pins inputs, copied sources, disassembly, classes and output.

Compile `tools/paradigm-file-audit/ParadigmHologramStoreAudit.java` once against
the installed provider, Gson and SLF4J. Run those same classes with each provider
in separate fresh output directories. The installed implementation fails
`existing_valid_file_stays_enabled`; the repaired candidate must pass all cases,
including corrupt-input retention and genuine externally held-reader failure.

Prepare fresh native fixtures with `paradigm-hologram-runtime-audit.py prepare`,
`--variant before|fixed` and an artifact-confined `--fixture`. This copies the
accepted integrated logging-recover image and hardlinks immutable provider
archives. The fixed fixture replaces only Paradigm. The helper requires the
accepted private-build manifest at its recorded artifact location.

Compile the native probe using `paradigm-hologram-runtime-audit.gradle` as an
init script with `paradigmHologramBuildDir` and `paradigmHologramFixture`
properties pointing to isolated artifacts, then run `:neoforge:compileParadigmHologramProbeJava`.
`package --fixture ... --build ...` appends the same single probe class to copied
Citizens archives, atomically replacing the owned hardlink entry. It never
overwrites a linked input. All inherited production entries and probes remain exact.

Run the original fixture's `seed` phase, then fixed `seed` and fixed `recover`
sequentially with `run --fixture ... --phase ... --java-home ...`. The listener
uses `PARADIGM_HOLOGRAM_AUDIT_PHASE`; existing probes skip the distinct integrated
selector. Both fixtures bind only `127.0.0.1:25642`. Phase attempts are single-use.
Observe the same process to terminal exit. Full-pack startup includes a slow
WorldEdit/CopyFoxies state scan; an observation timeout does not authorize replay.

The original phase must exit zero after reproducing the specific disabled-owner
failure, and is excluded from functional acceptance counts. Fixed phases inspect
actual provider-owned text-display entities and literal text, coexistence with a
Citizens hologram NPC, byte-stable live reload, entity UUID reuse without duplicates,
and configuration/definition recovery after native restart. This is server-side
entity acceptance; physical rendering/input remains a separate deferred gate.

Completed phases retain real process exits, logs, runtime inventory and canonical
SQLite snapshots. Accepted phases also retain stopped world/config/database images.
Failures are retained without replaying their world. Run
`record-paradigm-hologram-validation.py --output docs/<manifest>.json` only after
all phases stop. It independently verifies archive provenance, actual SQLite
contents, financial/history preservation, inherited journal/team/player bytes,
saved configuration, stopped entity NBT, protected prior fixtures and launch
rejection before Java. It freezes the final executable references in artifacts.

If a probe or recorder fails, preserve its source/log/result. Correct the recorder
against saved evidence; correct gameplay assumptions only in a new fixture seeded
from the last accepted stopped parent. Never rerun accepted gameplay to repair a
recording error. Distribution authorization and the supported Paradigm inbound API
remain distinct release requirements.

Vanilla rewrites the date comment in `server.properties` at startup. Restart
preflight reconstructs the original pinned seed input and permits comment-only
changes, requiring every settings line to remain exact. It also rechecks the
loopback address/port, world and disabled RCON/query explicitly, and captures
current launch/runtime input hashes. The original seed/reproduction results are
preserved with their earlier recorder shape; a failed comment-hash preflight
launched no Java process and did not replay gameplay.
