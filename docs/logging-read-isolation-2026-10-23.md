# GriefLogger repository reads and transaction outcomes

Date: 2026-10-23, UTC+08:00. Provider: Minecraft 1.21.1 / NeoForge
`grieflogger-1.2.10-1.21.1-yuuniverse.8-neoforge.jar`.

Public logging lookups could return records from an unfinished transaction which
subsequently rolled back. The worker pool permits lookups and queue flushing on
different threads. Both use one JDBC connection; the write monitor previously
covered only writes. SQLite's isolation between connections does not prevent
another operation on that same connection from reading its uncommitted changes.

The retained `.7` reproduction runs an actual immutable INSERT, pauses the
transaction at a second queued write, and calls actual
`UserRepository.getAllUsernames()`. The public map contains `{1=uncommitted}` while
an independent SQLite connection sees zero committed users. The second write then
throws; rollback succeeds and the public map becomes empty. The lookup had already
published a username that never committed. Both the original diagnostic with an
incorrect logger classpath and the corrected, completed reproduction are retained;
only the latter establishes the full rollback sequence.

Provider `.8` adds `Database.read`, using the existing transaction monitor from
statement preparation through parameter binding, result consumption and closure.
All nine public repository read paths use it: usernames, block history,
interaction history, filtered blocks, container position, container range,
filtered containers, filtered items and filtered sessions. SQL, filters, order,
limits and successful payload decoding retain their existing behavior. Explicit
result-set scopes close cursors before the read releases ownership.

A read on another thread waits for commit or rollback. A same-thread read from
an active write callback rejects with `SQLException`; Java's reentrant monitor
would otherwise permit a dirty read. Reads after an uncertain write outcome also
reject. Existing repository error logging and empty-or-partial fallback contracts
remain, so a logged failed lookup must not be interpreted as authoritative empty
history. This is a transaction boundary repair, with no new public error-result
model. External callers retaining raw JDBC statements still own their lifecycle;
they are outside the nine repaired repository paths.

The actual provider/embedded SQLite suite passes **143 assertions across 28
databases**, including all 109 preceding checks. New cases cover public lookup
waiting through commit and rollback, exact committed maps, a writer blocked while
an actual cursor is consumed, successful and failed cursor/statement closure,
SQL/decoder failure propagation, subsequent writer progress, same-thread rejection
and a real dirty prefix hidden after an injected rollback failure. The standalone
suite substitutes only the Minecraft bootstrap/logger owner, while database,
queue, repository and SQLite code come from the packaged provider.

The native audit uses a copied stopped logging/commerce world and all nine actual
repository APIs. Fixture-owned SQL records have their own random identity and a
previously unused position. They are test data, separate from gameplay evidence.
After inserting complete block, interaction, container, item and session rows,
a latch pauses the actual write transaction. Nine concurrent public lookups must
wait. An independent connection must still see every exact original committed
row. Rollback returns the original maps/empty position histories; commit returns
the exact expected identity, descending timestamps/actions, position, paper
quantity and decoded original native components. All original dictionary/history
rows remain unchanged. A new process repeats every lookup and complete database
snapshot comparison.

| Acceptance with `.8` | Assertions |
|---|---:|
| Actual provider and embedded SQLite | 143 |
| Nine public repository paths, rollback and commit | 61 |
| Fresh-process repository restart | 11 |
| Native logging, world rollback and commerce, then restart | 37 + 8 |
| Native final-after-last-tick shutdown, then restart | 11 + 8 |
| Coordinate indexes on a copied existing database, then restart | 26 + 25 |

There are **187 fresh native/restart assertions**. Separate stopped images retain
read, index and final-shutdown boundaries. Independent validation checks complete
SQL rows, components, identity joins, all six coordinate plans, original balanced
commerce receipts and saved shutdown inventory/world/entity state. Completed read
and recovery phases reject before launch, including when preparation is skipped.
Protected fixture files retain their hashes. The failed initial native audit
compilation is preserved; it used Minecraft `ItemStack` methods on the provider's
`SimpleItemStack`. The accepted fixture uses the actual provider API.

The [validation manifest](logging-read-validation-2026-10-23.json) pins provider,
patch, source commit, standalone/native outputs, executable references, original
ordinary evidence and preserved fixtures. All 163 nested driver files retain
their `.7` bytes. Citizens/Interactions production classes match the retained
ordinary packages; their preceding 828 tests per target and 100 Interactions
tests are reused, with report/package hashes checked. No new ordinary-suite run
is claimed for unchanged production/test/build sources.

To rebuild, apply the complete provider patch chain through `.7`, then
`tools/grieflogger-compat/repository-read-isolation.patch`, and build with Java 21.
For native reproduction prepare a fresh copied stopped fixture through
`tools/prepare-logging-index-audit.ps1`; this shared setup verifies stopped
ownership and records every original row. Run `reads` then `reads-recover` through
`tools/logging-runtime-audit.gradle`, using separate artifact build/cache paths.
Preserve completed/failed fixtures. The source commit's walkthrough records the
provider change; the root walkthrough records the complete acceptance batch.

The single shared connection serializes readers and writers, so a long query or
large flush can delay another operation. These bounded tests do not establish
sustained production throughput or MySQL acceptance. Retention tooling, physical
device failures, crash spooling, wider world/financial backup combinations,
original migration mappings/provider inputs, supported Paradigm inbound contracts
and deferred ordinary/VR/proxy clients remain release gates. The user's ordinary
client test remains deferred, with its stopped fixture preserved. The full goal
remains active.
