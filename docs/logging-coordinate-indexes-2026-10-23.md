# GriefLogger SQLite coordinate indexes

Recorded 2026-10-23, UTC+08:00. Advances ADAPT-09 lookup/load acceptance;
full integration and production performance acceptance remain open.

Provider `.6` enabled indexes by default but created only the blocks coordinate
index. All six history repositories used `CREATE INDEX IF NOT EXISTS coordinates`.
SQLite index names are database-wide: after blocks created that name, the other
five statements silently did nothing. Actual native schema inspection and query
plans reproduce full scans on chats, commands, containers, items and sessions.

The [incremental provider patch](../tools/grieflogger-compat/sqlite-coordinate-indexes.patch)
builds **1.2.10-1.21.1-yuuniverse.7** after `.6`. It assigns distinct SQLite names
`chats_coordinates`, `commands_coordinates`, `containers_coordinates`,
`items_coordinates` and `sessions_coordinates`. The existing blocks `coordinates`
index remains. Indexed columns, lookup SQL/order/limits, stored data and MySQL
statements stay unchanged. Startup adds missing indexes to existing databases;
repeated startup creates no duplicates. `useIndexes=false` skips creation and
retains any indexes already present, following the existing configuration policy.

Provider source commit: `8081e20069641ee78f1c0fe622088b9a9ca2ae97`, with author and
committer A-BenLi06 <ben_li06@outlook.com>. Its source walkthrough records the
reasoning and validation. Java 21 `:neoforge:build` succeeds in a separate source
checkout/cache. The exact patch applies after `.6` commit
`f35314d9947ea7cbc77df82a50ef41c5167954b0` and reverses cleanly.

The native audit starts from a copied, stopped `.6` logging/commerce fixture.
Every original history, identity and dictionary row must match its independently
recorded prelaunch snapshot before workload admission. A separate synthetic
identity owns 2,048 queued SQL observations per history table, **12,288 total**.
The real provider validates, queues and commits those parameters. Two observations
per table share the queried position; unrelated positions form the remaining
workload. Item/component bytes come from an original native container record.
Workload rows are fixture SQL data and are not presented as native gameplay.

The same audit bytecode reproduces `.6`'s missing chat index assertion after
committing the workload. Its saved plans show one indexed search and five full
scans. With `.7`, all six exact-coordinate queries use their own indexes and
return the exact expected rows in descending time order. Full snapshots preserve
every row/component/quantity through lookup, repeated initialization and a fresh
process restart. Independent SQLite reads verify the complete payloads, original
prefixes, dictionary identities, index columns and query plans.

A second copied fixture starts without indexes and with creation disabled. Only
its copied legacy index is removed during setup; no source fixture changes.
All six queries initially scan and still return exact results. Enabling creation
for the next process adds all six indexes without changing any data. This checks
the configuration and additive migration separately from ordinary enabled startup.

| Acceptance | Result |
|---|---|
| Actual provider and embedded SQLite | 109 storage assertions across 24 databases |
| Enabled existing-database indexes | 26 native plus 25 fresh-process restart checks |
| Disabled then enabled existing-database indexes | 24 native plus 25 restart checks |
| Normal logging/rollback/commerce | 37 native plus eight restart checks |
| Final-after-last-tick logging | 11 shutdown plus eight restart/second-stop checks |

There are **164 fresh native/restart assertions**. Every accepted invocation
reaches terminal BUILD SUCCESSFUL without a final-drain error. Four completed-phase
launch attempts reject even with preparation skipped, preserving protected hashes.
Three preparation checks reject an existing destination, an outside-artifact path
and a destination inside its source fixture. The last guard was added after
fixture creation; both the exact creation-time script and the final script are
retained, and the validator verifies that this guard is their only difference.
Seven fixtures and two shutdown world/database images pass exclusive stopped
ownership; four index-phase database snapshots retain their separate schema states.

The [validation manifest](logging-index-validation-2026-10-23.json) pins provider,
source, patch, logs, original/workload snapshots, stopped databases and executable
references. It records checks for native normal logging/rollback/commerce and
final-after-last-tick shutdown as separate regressions. The original Economy
operation, receipt, balance and balanced ledger remain authoritative; adding a
logging index does not reverse money. Shutdown regression independently decodes
the original player inventory, drop and native block/chest state from stopped NBT.
All 163 nested SQLite driver files retain their `.6` bytes and selectable
packaging. Citizens/Interactions production classes match all five native builds.
The preceding 828 ordinary tests per target and 100 Interactions tests are
explicitly reused for unchanged production/test/build sources with original
package/log/report hashes verified; no fresh ordinary test run is claimed.

To reproduce, apply the complete provider patch chain through `.6`, then the
coordinate-index patch and build with Java 21. Use
`tools/prepare-logging-index-audit.ps1` with a fresh artifact-confined fixture and
the `.7` provider. Its default source is the retained stopped `.6` normal fixture;
another compatible stopped fixture can be supplied explicitly. Run `indexes`
then `indexes-recover` through `tools/logging-runtime-audit.gradle`, with separate
artifact build and project-cache directories. For the configuration case prepare
with `-DisableIndexes`, run `indexes-disabled`, enable `useIndexes` in that stopped
fixture, then run `indexes-recover`. Completed phases reject before launch even
when preparation is skipped. Preserve original and failed fixtures.

These are bounded coordinate-query plans and exact-data checks. They do not
establish a production service rate, remove sorting from every radius query or
lift public lookup limits. No timed expiry, history deletion, dictionary pruning
or crash spool is introduced. Retention tooling, sustained load, MySQL acceptance,
broader recovery/backup combinations, original migration inputs, supported
Paradigm inbound evaluation and deferred ordinary/VR/proxy clients remain release
gates. Production and BenLi06's deferred fixture retain their earlier artifacts.

SQLite reference: [CREATE INDEX](https://www.sqlite.org/lang_createindex.html).
