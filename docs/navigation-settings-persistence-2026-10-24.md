# Navigation settings persistence

2026-10-24, UTC+08:00. Citizens core on Minecraft 1.21.1.

`/npc pathopt` could change attack range, attack delay and path distance margin,
but `CitizensNavigator.save/load` omitted all three. A clone, reload or restart
therefore restored defaults instead of the configured NPC behavior. The
serializer now saves their non-default overrides and loads explicit zero and
signed values. Resetting a value to its default clears the old saved key.

Loading a snapshot with no override keys also restores these three defaults
on an existing navigator. The initial persistence repair exposed a stale-value
case during review: a live attack range of 7.25 survived loading a default
snapshot. A focused failing regression confirms that case. Legacy snapshots
without the new keys inherit current configured defaults. Attack delay uses
the existing public API default, without a separate hardcoded policy.

Four initial unit tests reproduce three failures against `fbc5862`; the legacy
default case already passed. A fifth test reproduces default-snapshot restoration
against the first repair. Complete ordinary `test build` runs now pass on
NeoForge 21.1.233 and 21.1.248: 847 tests in 118 suites per target, with zero
failures, errors or skips. All five focused persistence tests pass.

The opt-in native audit uses actual command dispatch on ordinary and packet
pigs. It covers exact snapshots, cloning, restoration onto a live NPC, default
snapshot restoration, registry reload, copying restored defaults into new
requests and real walking with teleport rescue disabled. Late command edits
are left unsaved until normal shutdown. A separate JVM recovery must retain
identities and values, drive another walk, reset values by command, reload again
and prove stale keys stay absent. Existing navigation parameters and persistent
metadata are checked throughout.

The original native control fails at
`ordinary_snapshot_contains_attack_settings` and stops normally. A first repaired
seed passes 62 checks but is superseded by the stronger default-snapshot case;
its world, log, source and unit reports remain preserved without replay. The
final seed passes 84 checks and recovery passes 38 checks on each target,
244 accepted checks total. All four accepted JVMs exit zero after normal
shutdown. No intermediate result is counted as final acceptance.

Independent verification passes exact stopped NPC YAML against recorded
snapshots, identities, saved values, removed overrides, configured defaults and
arrival coordinates. The ordinary 1,024 Citizens class files per target match
their native counterparts; Citizens/source/Interactions archives preserve their
separate contents and exclude test/audit code. The
[validation manifest](navigation-settings-validation-2026-10-24.json) pins the
source, unit XML, native evidence and packages.

Replay testing exposed a tooling defect: Gradle can skip `prepareServerRun` as
up-to-date, bypassing a guard installed only in that task. The first attempted
baseline replay started Java; its phase marker rejected audit admission before
scenario actions. Server properties/logs/world files and YAML byte ordering
changed during startup/shutdown. NPC snapshot semantics remain exactly equal to
the accepted recovery record. The complete failed launch/change evidence is
retained; it is excluded from acceptance totals.

Both navigation audit launchers now check again immediately before Java
execution. Tests explicitly skip preparation to reproduce the cache condition:
both are rejected at execution preflight, with all 60 settings-fixture files
and all 45 earlier command-fixture files unchanged. The earlier fixture and its
accepted build outputs remain untouched. The initial independent recorder also
assumed an explicit `spawned: true`; the existing persistence layer can omit that
default after loading, so verification now recognizes the source-defined trait
default. No gameplay was repeated to repair recording.

The [audit instructions](../tools/navigation-settings-runtime-audit/README.md)
describe separate fixture/build/cache paths and phase guards. Other navigation
scalar fields still use conditional loading when default override keys are
absent; review their snapshot restoration separately. Physical ordinary-client acceptance
remains deferred. No production restart, provider activation or release publication
is included, and overall Citizens completion remains unproven.
