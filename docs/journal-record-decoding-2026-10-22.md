# Preserve journal record types during recovery

Recorded 2026-10-22, UTC+08:00. Extends the retained delivery, crafting,
monetary reward and commerce evidence contracts. This changes decoding, not
their schemas, immutable publication, state transitions or ownership boundaries.

The previous four loaders checked field names but let Gson reinterpret field
values. A schema of 1.5, "1", [1] or 4294967297 could become schema 1;
fractional quantities could truncate and oversized quantities could wrap.
String booleans, duplicate members and unquoted member names also loaded.
For example, a reward amount of 375.5 loaded as 375, and a commerce receipt
amount of 18446744073709551646 loaded as 30. Altering every retained revision
consistently bypassed the existing comparisons between revisions.

A before-change matrix reproduced all 36 cases: four journal types against
nine damages. Every original loader accepted its altered history, making all
36 rejection assertions fail. Exact original sources, generated histories,
damaged files, observations and XML/logs remain in task artifacts.

The shared package-private JournalJson reader now detects duplicate object
members and uses Gson's non-lenient streaming reader. Its iterative document
builder avoids recursive parsing of arbitrary input containers. Before Gson
constructs any record, validation follows the record's declared fields and
generic types, including nested records and commerce cursor evidence lists.
Numbers must be JSON numbers and exactly represent the declared int/long;
strings and booleans must have their actual JSON types. Unsupported enum names
reject, including optional enums that Gson otherwise converts to null. Record
constructors still enforce required values and domain invariants. Cached record
metadata follows declarations rather than maintaining a second field schema.

Legitimate nullable fields, whitespace and mathematically integral notation
such as 1.0 remain accepted. This is not a complete RFC JSON lexical validator:
Gson's non-lenient reader retains some legacy lexical behavior. The guarantee
here covers exact record types, duplicate members and the demonstrated syntax
cases. Embedded inventory/evidence JSON strings retain their existing domain
validation; this change does not reinterpret their contents.

All 36 damaged cases now reject on both supported NeoForge targets without
rewriting damaged files. Failed loads release ownership; restoring the exact
originals reopens normally. Four positive controls preserve valid numeric notation,
whitespace and null fields. An additional control proves an unknown optional
craft outcome cannot disappear into restart recovery: rejection appends no
revision; restoring its legitimate original null allows normal REVIEW/MISSING.

The opt-in historical reload probe uses fresh artifact copies, holding the
original journal's exclusive ownership while copying. It checks every exposed
head payload against the retained original, including amounts, beneficiaries,
native targets and complete evidence strings. Commerce cursor queries retain
their original positions. A second reopen must be stable, every original image
must remain byte-identical, and only normal missing-outcome recovery revisions
are allowed. Six samples reopen 272 historical files and 95 heads across all
four journal types, including rejected missing assembly outcomes, applied
assembly outcomes and Create transport evidence. These samples require no
additional recovery revisions.

Full validation passes 823 ordinary tests on each of NeoForge 21.1.248 and
21.1.233, plus 100 separate Interactions tests. Native regression passes 302
checks: callbacks61, normal17, historical restores45, storage17, selected
commerce/reward104 and six actual isolated JVM kills with boundary18/recovery40.
Each final job reaches terminal success. Six ordinary archives exclude audits,
audit mixins and foreign implementations, preserve separate mod ownership,
match final sources and include the same changed production class bytes tested
by native and historical reload probes, including JournalJson's nested classes.
Stopped world/journal/database ownership, absent SQLite sidecars, immutable
read-only integrity, balanced ledger postings and original prepared settlement
identities are verified in the [validation manifest](journal-record-decoding-validation-2026-10-22.json).

Two initial PowerShell invocations split unquoted Gradle properties and stopped
during configuration; explicit argument arrays corrected them. A later XMod run
completed all 104 assertions but exceeded its three-minute timeout during slow
shutdown under concurrent load. A fresh fixture completed successfully with an
isolated six-minute task allowance. Diagnostics are retained; production code
was not changed for those invocation or timing failures.

Damaged histories require review and consistent original backups; this change
does not repair, prune or overwrite them. Full-history load/retention, broader
device/provider failures, other recovery boundaries, original migration
provenance and physical ordinary/VR/proxy acceptance remain separate gates.
Production and BenLi06's deferred, stopped client fixture remain unchanged.
