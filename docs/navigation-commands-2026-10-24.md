# Citizens navigation commands and virtual movement

2026-10-24, UTC+08:00. Citizens core on Minecraft 1.21.1.

`/npc pathopt` previously published earlier flags before validating later values.
A command rejected for an invalid attack range could still change water policy,
teleport policy and persisted metadata. The command now validates the complete
edit and converts the attack duration before publishing changes. Speed rejects
NaN, infinities and values overflowing its stored float; velocity rejects
non-finite coordinates. These three commands also use strict argument handling.
Existing signed duration and falling-distance compatibility is preserved.

Actual walking then exposed two additional core defects. Virtual NPCs had no
native entity tick to consume navigation/move controls. Active, unpaused
navigation on an unmounted virtual root now receives the normal native entity
tick and passenger traversal during its level's tick. Frozen entities remain
frozen; paused and idle roots retain their decorative behavior. Virtual riders
still use native attachment positioning without an independent simulation tick.
Real passengers are traversed exactly once. Citizens retains ownership checks
for the current trait, entity and registry.

Vanilla's coordinate `moveTo` overload searches within one block of its target.
Both ordinary and virtual pigs could finish on the adjacent block, over a block
away from the requested coordinates. Citizens location navigation now creates
the path with zero target-block tolerance before handing it to vanilla. This
requests the specified block; vanilla's own node arrival behavior still applies.
It does not promise arbitrary sub-block precision or change entity chasing.

Both NeoForge 21.1.233 and 21.1.248 pass the complete ordinary `test build`:
842 tests in 117 suites per target, with zero failures, errors or skips. Six new
regression tests failed against the original commands and pass with the fix.
The native original-source control at `9c8a8f7` reproduces partial mutation.

The final native navigation fixture passes 229 checks per target, 458 total.
Actual Brigadier dispatch covers rejected compound edits, bounds, durations,
non-finite values, overflow, enum/boolean parsing, unknown flags, permissions,
valid values and request ownership. Both NPC transports walk out with Citizens
pathfinding and return with vanilla pathfinding after rejected edits. Teleport
rescue is disabled. Paused/idle simulation and native passenger tick counts are
checked across real server ticks.

Accepted navigation fixtures are stopped at
`artifacts/navigation-command-final-{233,248}-server`, with corresponding
`navigation-command-native-final-{233,248}-build` outputs and logs. Ordinary
outputs are `navigation-command-core-build` and `navigation-command-selected-build`.
Original failures and both intermediate navigation timeouts remain preserved.
The first timeout proves the virtual pig stayed at spawn; the second proves
both pigs stopped on an adjacent vanilla path block. Neither is accepted.
The initial fixed unit failure was a test tokenization mistake: quote
`-Infinity` to exercise numeric validation instead of short-flag parsing.

Fresh 21.1.248 regressions pass 393 Interaction-label checks and 170 packet
hologram/mount checks, giving 1,021 accepted native checks in this batch. All
four accepted native processes exited zero after normal shutdown. Independent
verification passes both stopped navigation fixtures: exact NPC YAML versus
recorded snapshots, UUIDs, finite state, final arrival coordinates and absence
of duplicate NPC native persistence. All 1,024 ordinary Citizens class files
per target match their native fixture counterparts; the selected target also
matches both regression builds. Ordinary Citizens, sources and Interactions
packages exclude audit/test code and retain their separate namespaces.

The accepted-fixture replay request fails at `prepareServerRun`, before Java
starts, and all 142 protected fixture files retain their hashes. The independent
partial-verification log is retained; it stopped because the requested
regressions were still pending. No gameplay was repeated to repair recording.
The durable [validation manifest](navigation-command-validation-2026-10-24.json)
pins sources, XML results, packages, native evidence and excluded attempts.

Review also confirmed a remaining persistence gap: `CitizensNavigator.save/load`
omits command-edited attack range, attack delay and path distance margin. The
native tests above verify those live settings. The subsequent
[navigation settings repair](navigation-settings-persistence-2026-10-24.md) now
adds cloning, reload and full-restart persistence coverage for those fields.

Workflow: [native audit instructions](../tools/navigation-command-runtime-audit/README.md).
Physical ordinary-client acceptance remains explicitly deferred. No production
restart, external provider implementation or release publication is included.
These bounded checks do not establish overall Citizens completion.
