# Native fire and explosion logging

Date: 2026-10-22, UTC+08:00. Advances ADAPT-09/11 with NeoProtect 1.0.4,
GriefLogger 1.2.10-yuuniverse.1 and the selected NeoForge 21.1.248 stack.

The environmental fixture reproduced an actual logging gap: a player ignited fire
with native flint-and-steel use, vanilla fire ticks destroyed wood, and NeoProtect
returned no record for that wood. `FireBlock.tick` and `checkBurnOut` write blocks
without the placement events that the installed logger subscribes to. Its
null-entity fire handlers also interpret `EntityPlaceEvent.getPlacedBlock()` as
the result, although NeoForge supplies the snapshot's old state for that context.
In particular, its old air-destruction filter cannot represent a non-air → air
change from that argument.

## Correction and ownership

Citizens observes the actual vanilla fire writes at the four set/remove invocation
sites in those native methods. It captures the original block/NBT snapshot, calls
the original operation and checks its resulting state. Same-block changes such as
fire age updates are excluded. A write that leaves the block type unchanged
produces no observation. The observation also runs when an operation throws after
a real change; the original native exception still propagates.

The retained snapshot is passed directly to NeoProtect's public fire handlers.
A provider-specific mixin makes those handlers inspect the resulting world state
for null-entity observations. No cancellable placement event is posted to the
global event bus after a completed mutation. Other event subscribers do not gain
an artificial placement/cancellation opportunity. Player ignition keeps its
existing native placement path and is recorded once.

NeoProtect continues to own actor attribution, configuration, serialization,
database records and rollback. A burned block replaced by fire uses its native
BLOCK_PLACE action with the original wood snapshot; a burned block replaced by
air uses BLOCK_BREAK. The bridge does not force either result or change fire
probabilities, damage, item drops or player inventories. `trackFire=false` remains
effective. Absent NeoProtect bypasses capture and resolves no provider classes.
Signature incompatibility is reported; a transient handler/storage failure is
reported and later observations can retry. No durability or replay of a failed
provider write is claimed.

The hook descriptors and handler behavior are pinned to the inspected Minecraft
1.21.1 / NeoProtect 1.0.4 implementation. Provider upgrades and another mod that
adds its own fire observations need coexistence acceptance. No foreign provider
implementation is copied into Citizens or Interactions.

## Native acceptance

The opt-in [audit](../tools/environment-logging-runtime-audit.gradle) uses a fresh
synthetic actor, embedded Minecraft connection, actual flint use, actual vanilla
fire tick methods with seeded randomness, and actual TNT explosion computation.
It queries the installed loggers independently after native changes. It does not
post synthetic fire/explosion events to make its assertions pass.

Coverage includes:

- Original igniter retention for actual burning, exact original wood/result
  snapshots and corresponding logger action, and one native ignition placement.
- Native distant fire with no player ignition and distant unowned TNT, retaining
  an unknown actor rather than borrowing the player's nearby cause record.
- Player-owned TNT with one original actor record per destroyed target block.
- Native burning with logging disabled, without fire records or inhibited fire.
- GriefLogger's independent boundary: it does not fabricate player break events
  for environmental explosions. That release does not provide equivalent general
  environmental history; NeoProtect owns those accepted records.
- Clean restart with the original actor and affected positions, and native fire/
  explosions while the optional NeoProtect classes are absent.

NeoProtect's cause inheritance is its configured nearest-recent-cause policy,
not proof of an unrestricted causal chain. The fixture separates unknown causes
beyond its 64-block default radius and retains the original run time to select
unknown records from repeated fixture runs. This selection is an audit filter;
there is no time-based production deduplication or invented actor mapping.

The existing 37-check logger/container/world/Economy workflow and eight restart
checks are rerun through actual forced JVM termination with the new production
source. Ordinary builds and the separate Interactions suite remain separate
checks. Exact counts, current source/provider/archive hashes, the original failing
log, successful native/restart/absent logs and fresh termination evidence are in
[the validation record](native-fire-logging-validation-2026-10-22.json).

The final source passes 30 native environmental checks, 24 clean-restart checks
and six checks without NeoProtect. The separate actual process-kill regression
passes 37 pre-kill and eight recovery checks. Clean ordinary builds pass all 775
tests on each NeoForge target; the separate Interactions suite passes all 100.
All six ordinary archives preserve mod ownership and exclude the runtime audits
and foreign providers. Read-only SQLite integrity checks after shutdown return
`ok` for the independent GriefLogger and Economy stores; NeoProtect's H2 evidence
is its native history/restart assertions, not an SQLite check.

An initial absent-provider run exposed provider-typed lambda descriptors in the
audit subscriber. NeoForge reflects subscriber methods before choosing an audit
phase. Those provider-specific checks now live in a lazily resolved nested class;
the successful absent run uses no NeoProtect JAR. This was a fixture correction,
not an invented provider replacement. The result-state lookup also stays inside
the production observation error boundary so a failed lookup cannot replace the
native operation's result or exception.

## Reproduction and limits

Use PowerShell 7 and Java 21, with a separate Gradle output and project cache:

```powershell
./tools/prepare-logging-audit.ps1 -FixtureDirectory artifacts/environment-logging-audit-server -ServerPort 25642 -WorldName environment-world
./neoforge/gradlew.bat -p neoforge --project-cache-dir ../artifacts/environment-logging-cache -I ../tools/environment-logging-runtime-audit.gradle '-Pneo_version=21.1.248' runServer --console=plain
./neoforge/gradlew.bat -p neoforge --project-cache-dir ../artifacts/environment-logging-cache -I ../tools/environment-logging-runtime-audit.gradle '-Pneo_version=21.1.248' '-PenvironmentAuditPhase=recover' runServer --console=plain
```

The absent phase uses a separately prepared fixture at port 25643 with world
`environment-absent-world` and its NeoProtect JAR moved out of `mods`. Preparation
rejects an existing fixture with a different world name and refuses a held native
world session lock. Provider/native phases run sequentially against their own
directories. Production and the pending ordinary-client fixture are unchanged.

This closes the stated vanilla fire write and bounded environmental attribution
cases. Lava ignition, mod-specific fire implementations, arbitrary cause chains,
write-interior crashes, queue overflow/storage failure, timed retention,
production load and integrated physical-client/migration acceptance remain open.
