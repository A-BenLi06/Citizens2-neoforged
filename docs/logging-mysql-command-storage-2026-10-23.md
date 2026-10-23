# Complete MySQL command storage

Recorded 2026-10-23, UTC+08:00. Follows the [repository read repair](logging-read-isolation-2026-10-23.md).
The [validation manifest](logging-mysql-validation-2026-10-23.json) pins the provider,
source commit, incremental patch, original failure, native observations, independent
SQL snapshots, restart, stopped fixtures and executable references.

## Reproduced loss and repair

GriefLogger `.8` silently truncates native commands to 256 characters on MySQL.
Its `commands.command VARCHAR(256)` and `INSERT IGNORE` combination admits three
actual command events, commits the shortened strings, and reports no pending work.
The final unchanged-provider reproduction uses the same audit bytecode as `.9`:
the 23-byte command survives, while the 1,032-byte and 69,025-byte commands become
256 characters. A prior reproduction and successful 37-check normal MySQL baseline
are retained separately. These are running Minecraft command events through the
real provider and MySQL, using a synthetic connected player rather than physical
client input.

Provider `.9` creates `MEDIUMTEXT NOT NULL` for MySQL commands. A valid command
within Minecraft's 32,767 UTF-16-unit bound can exceed MySQL `TEXT`'s 65,535-byte
capacity; the accepted Unicode example does. Existing known `VARCHAR`/`TEXT`
columns widen while retaining their actual character set and collation. Existing
`MEDIUMTEXT`/`LONGTEXT` columns remain intact. Unfamiliar narrow column attributes
disable logging admission with an explicit schema error instead of guessing their
meaning. Plain `INSERT` replaces `INSERT IGNORE` for commands. SQLite command SQL
and already stored historical text remain unchanged; lost suffixes are not invented.

Schema initialization now owns statements, records SQL/runtime failures, attempts
rollback and prevents `completeInitialization()` from reporting readiness after a
failure. This preserves the existing event-registration boundary. MySQL DDL can
commit implicitly: the fence prevents logging admission but does not promise to
undo earlier successful schema changes. Preserve a database backup before adopting
the migration. No production database or deployment was changed by this batch.

Build the exact incremental [patch](../tools/grieflogger-compat/mysql-command-capacity.patch)
after `.8`; forward and reverse apply checks are required. The separate Java 21
provider build produces
`artifacts/grieflogger-mysql-command-source/neoforge/build/libs/grieflogger-1.2.10-1.21.1-yuuniverse.9-neoforge.jar`.
All 163 nested SQLite files retain their preceding bytes and selectable packaging.

## Bounded acceptance

The isolated official MySQL image resolves to **8.4.13**, using packaged
**Connector/J 8.4.0**, default strict SQL mode and `utf8mb4`. Its port is published
only at `127.0.0.1:33317`. Container ID, immutable image identity, repository digest,
SQL dumps and lifecycle evidence are retained under task artifacts. Fixture
credentials stay in ignored local files and are excluded from the manifest.

| Check | Accepted assertions |
| --- | ---: |
| Actual provider and embedded SQLite storage/initialization regressions, 32 databases | 156 |
| Actual provider and MySQL lock/constraint/read/uncertain-outcome checks | 38 |
| Four native MySQL command cases and fresh-process recovery | 72 |
| Unsupported narrow custom schema rejection | 4 |
| Native MySQL normal logging, NeoProtect rollback, Economy and recovery | 45 |
| Fresh SQLite normal, final shutdown, coordinate index and public read regressions | 187 |

The native/restart total is **308**; the standalone storage totals are separate.
Every accepted native job must reach terminal successful completion. The expected
old-provider failure is excluded from accepted counts.

Command cases cover new schema, existing legacy `VARCHAR`, existing `TEXT`, and
existing larger `LONGTEXT` with a custom comment. All three exact strings, quotes,
backslash, Unicode, actor, world and coordinates survive. Original truncated rows
remain exact alongside new complete records. The binary collation is retained;
the larger column and its comment are not narrowed or rewritten. A narrow column
with a custom comment is rejected without changing its original table data/schema.

Real InnoDB lock timeout 1205 rolls back both ordinary and batch prefixes; releasing
the lock retries original owned UTF-8/binary parameters once. Duplicate-key 1062
rolls back a real driver batch; removing the conflict recreates the batch and
retains null/distinct binary values. An exception injected after an actual MySQL
COMMIT models a missing reply and verifies the uncertainty fence. This is not a
real network failure test. Public repository reads wait across actual commit and
rollback, and an independent connection sees no dirty user.

Independent SQL capture records all isolated databases before and after a graceful
stop/start of the same MySQL container. Every row, column attribute and index must
match. The final container stop retains its bind-mounted data and releases the
loopback port. Completed-phase guards, including skipped preparation, preserve
protected fixture hashes. World/SQLite/H2 ownership checks establish stopped
fixtures. Historical failed and accepted fixtures are retained.

Citizens/Interactions production, ordinary test and build inputs are unchanged.
Accepted native builds match the retained release classes; the preceding **828
ordinary tests per target** and **100 Interactions tests** are reused with their
original reports/hashes. They were not rerun in this batch. BenLi06's deferred
ordinary-client fixture and pinned provider/property hashes remain unchanged.

## Remaining acceptance

These cases establish complete command storage and the stated recovery boundaries,
not full MySQL/backend equivalence. Every entity/dictionary interaction, external
writer isolation, sustained throughput, retention, forced MySQL termination,
physical device/network loss, and world/log/financial backup combinations still
need acceptance. The queues remain memory-only before commit; uncertain outcomes
require review.

Original quest mappings, missing ItemEdit definitions 6/15/144 and the missing
item provider remain migration inputs. Paradigm inbound evaluation still needs
its supported provider contract. Ordinary-client testing is deferred by the user;
VR/proxy/device and integrated release gates remain open. The full goal remains
active, with no production activation or deferred client restart.
