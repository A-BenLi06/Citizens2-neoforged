# Entity history registry references

GriefLogger `.9` could silently discard native MySQL entity deaths. `blocks.type`
stores a material ID for block actions and an entity ID for kill action 3, but its
foreign key always referenced `materials`. With an empty material dictionary,
three actual player kills created three entity names and no history rows while
flush returned true and both queues were empty. The retained `.9` fixture fails
the same entity audit bytecode used for the repaired provider. Historical missing
deaths cannot be reconstructed from these dictionaries.

Provider `.10` preserves the eight original history fields and public lookup SQL.
It adds generated `material_type` and `entity_type` references selected by the
persisted action. MySQL uses STORED columns and one atomic ALTER to replace the
incorrect foreign key. SQLite transactionally rebuilds the known legacy table
with VIRTUAL columns while retaining original rowids, index definitions and
triggers. Startup checks the retained references. SQLite's native foreign-key
configuration is unchanged; standalone restrictive-write checks explicitly enable
foreign keys. MySQL block/entity inserts now use ordinary INSERT so constraint
errors reach the existing rollback/retry handling.

Unsupported schema semantics and unresolved references disable logging admission.
SQLite additionally rejects migration-table collisions and inbound foreign keys
that make rebuilding unsafe. Later optional index DDL now holds the database
monitor and stops after a schema failure. Native rejection fixtures retain all
eleven original tables and deliberately lack the block coordinate index; their
stored schema and owning connection must both remain unchanged. MySQL DDL has
implicit commit boundaries, so this does not promise database-wide startup
rollback.

The [validation manifest](logging-entity-validation-2026-10-23.json) records the
final build, incremental patch, provider source commit, installed JARs, audit
bytecode, SQL dumps, stopped storage, fixture guards and executable references.
Apply `tools/grieflogger-compat/entity-registry-references.patch` after `.9`.
The final provider SHA-256 is
`ed433e6fa6cae9ce1574ff60255ac2fea6aedbabfc4b5b21596047bc5a99e94f`;
source commit is `d5aa191` in the retained provider checkout.

| Final acceptance | Assertions |
| --- | ---: |
| Actual provider SQLite storage / schema | 156 / 28 |
| Actual provider MySQL schema / storage | 26 / 38 |
| Native SQLite and fresh-process recovery | 230 |
| Native MySQL and fresh-process recovery | 106 |

Fresh entity cases kill pig/cow/sheep before any material is logged, then add a
real stone placement and two more kills. Public lookups and independent SQL must
retain all five deaths and the material event. Migration cases preserve all 199
original block histories and every other original table, then repeat startup and
restart. The final matrix also checks full Unicode commands, normal world and
container rollback, item components, exact commerce receipts/balance and balanced
ledger, final-after-last-tick shutdown, coordinate query plans and all nine public
repository reads. Six completed-phase launch attempts reject before Minecraft
starts, including skipped preparation.

Prototype fixtures remain retained and are excluded from final totals. A command
fixture copied an intermediate build archive before SQLite Jar-in-Jar packaging
finished. It is also excluded; fresh final fixtures use the frozen completed JAR.
An initial standalone runner incorrectly expected a nested MySQL driver; the
actual provider shades Connector/J. Its failed runner is retained, and the
completed tests use the actual packaged driver. An optional compilation preflight
also failed because direct PowerShell property arguments were split; native
runners retain structured argument arrays. Neither failure required changing
production behavior.

The prior `.9` MySQL fixture was briefly restarted before this batch moved to a
new isolated container. All 106 tables across its ten databases were independently
verified unchanged and it was stopped again. Eight internal MySQL files changed
through that restart; the supplementary
`artifacts/logging-entity-prior-mysql-brief-restart.json` records their old/current
hashes. One additional binary-log file was also created; the current regular-file
manifest pins all 280 files after that stop. The earlier raw-file hashes describe its earlier stop and must not be
presented as current. Its immutable SQL dumps and snapshots remain unchanged.
This batch uses a separate MySQL 8.4.13 container on loopback port 33318, captures
its SQL, stops it gracefully and retains regular data files with final hashes.
Minecraft process restarts are tested; a MySQL engine restart is not claimed for
this batch.

Citizens and Interactions production classes match the retained ordinary
packages. Existing 828 tests per target and 100 Interactions tests are reused
only for unchanged production/test/build inputs with pinned reports and package
hashes. This is bounded backend acceptance. Sustained load/retention, broader
world/log/financial and physical/network recovery, unavailable original quest and
ItemEdit migration inputs, supported Paradigm inbound traffic and physical
ordinary/VR/proxy clients remain open. The user deferred the ordinary-client run;
its stopped fixture remains unchanged. No production activation is included.
