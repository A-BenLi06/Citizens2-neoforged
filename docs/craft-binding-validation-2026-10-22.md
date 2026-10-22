# Validate craft bindings before publishing restart recovery

Recorded 2026-10-22, UTC+08:00. Advances CRAFT-05 and ADAPT-04/05/11.

Craft binding startup still used Gson's coercing record decoder after checking
field names. The retained baseline accepts fractional/string/overflow schemas,
fractional/overflow maxima, a string boolean, duplicate and unquoted schema
members, and a fractional timestamp. This can silently change the persisted
binding's meaning. The baseline also opens and recovers its journal before
checking bindings: unsupported or incomplete bindings reject startup after an
original PREPARED admission has already acquired a REVIEW/MISSING revision.

Binding reads now use the shared JournalJson parser and record-type validator.
Numbers must exactly fit their declared int/long types; strings and booleans keep
their JSON types. Duplicate members and unsupported fields reject. Existing
Binding construction still validates schema, UUIDs, object and recipe identities,
revision, maximum and timestamp. Valid integral decimal notation and whitespace
remain supported. This uses the previously accepted Gson non-lenient reader; it
does not establish a complete RFC JSON lexical validator.

A package-private journal opening callback validates companion bindings after
exclusive journal ownership and complete history validation, before any missing
native outcome recovery writes. Reading bindings before acquiring the lock would
allow competing writers; this ordering preserves ownership throughout startup.
Failure closes the lock/channel, and CraftQuestEvidence registers only after a
successful open. The public journal open retains its previous recovery behavior.
No journal schema, binding publication format or native projection rule changes.

The native audit copies three real vanilla-crafting bindings and their immutable
admissions from the preceding accepted fixture, holding its journal lock during
the snapshot. Every damage gets its own copy, retained before/after images and
exact-original restoration. The two publication cases retain the original native
PREPARED prefix by omitting later revisions only in the fresh copies. They do not
fabricate a returned outcome or post a synthetic craft event. Valid restoration
performs the legitimate REVIEW/MISSING recovery and proves failed-load ownership
release. Positive copies retain all three bindings and entries, accepting pretty
printing and integral decimal values without rewriting records.

Fresh native binding acceptance passes 23 checks on each of NeoForge 21.1.248 and
21.1.233. All eleven damages reject without changing durable record images.
The original native source remains identical to its preceding acceptance hashes.
The actual crafting/offline restart/independent-target repair regression adds
seven seed and eleven recovery checks per target; original admissions remain
unchanged and stopped FTB team files retain all three totals at one. Together
these runs pass 82 native checks. The broader Create regression passes 263 checks
in a fresh 21.1.248 fixture across table, machine, batch, transport, basin, fluid,
deployer, fan and assembly behavior.

Both clean ordinary builds pass 825 tests each with no failures, errors or skips.
Two new tests verify exclusive ownership and zero publication after companion
failure, plus rejection of damaged journal history before companion validation.
The prior 100 Interactions checks remain attributed to their original run;
dialogue/audit sources and the recorded log are unchanged. Six ordinary archives
exclude audit/foreign implementations, retain separate mod ownership, match final
sources and contain the same changed/shared class bytes as the corresponding
native runs, including the new OpenValidation interface.

Exact source, providers, packages, logs, native saves, retained damaged histories
and before/final observations are in the
[validation manifest](craft-binding-validation-2026-10-22.json). Fresh fixtures
and the historical source are stopped with exclusive ownership of five worlds
and 65 journals.
The bindings audit is opt-in through the existing artifact-confined recovery
runner and remains outside ordinary artifacts.

This closes demonstrated binding coercion and recovery publication before invalid
companion rejection. Wider crash/world-backup/financial/provider/retention matrices,
original migration inputs, supported Paradigm inbound evaluation and deferred
physical ordinary/VR/proxy acceptance remain open. The full goal remains active.
