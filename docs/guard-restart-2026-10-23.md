# Guard Villagers native restart acceptance

Recorded 2026-10-23, UTC+08:00. Advances ADAPT-08/11; integrated release remains open.

The [earlier coexistence audit](guard-villagers-coexistence-2026-10-18.md)
round-tripped a guard's NBT in memory. This follow-up verifies clean native world,
player and Citizens-store restart on **NeoForge 21.1.248 and 21.1.233**, using the
same pinned Guard Villagers 2.4.12 provider. Citizens/Interactions production code
and previously accepted ordinary archives are unchanged.

Each fresh fixture recruits two ordinary villagers through the actual native
interaction handler. Recruitment consumes the original named weapon. A named
chestplate with damage nine and a named shield populate each guard's inventory.
Decoded native server payload handlers set one guard to follow its owner and the
other to patrol its current anchor. The fixture also persists five default-registry
NPCs: ordinary and native-AI villagers, a packet villager, and ordinary/native-AI
iron golems. NPC professions use their public persisted trait.

The public registry save method, native player save and native world save run
before a clean stop. The next process obtains the guards by their original UUIDs
from the native world and the NPCs from Citizens' automatically loaded registry.
It does not construct replacement guards, import test NBT or replay recruitment.
Before owner reconnect, both guards retain their owner UUID while getOwner returns
null. Complete six-slot inventories, item components, follow/patrol flags, patrol
anchor, names and entity identities equal the original snapshots.

All five NPC IDs/UUIDs/entity UUIDs, professions, AI settings and packet/world
backends survive. There are no duplicate NPC records or recruited guards. The
returning player uses the original owner UUID, which resolves to the new live
player object. Sneak-click recruitment attempts preserve ordinary/native-AI/packet
NPC identity, the held weapon and one Citizens click event. Assistance still
assigns native guards and opted-in golems while leaving Citizens-owned golem AI
under Citizens control.

Actual server ticks run the restored guards' native follow and patrol goals.
Both decrease their distance by more than half; the audit permits the provider's
patrol admission delay and concurrent native stroll behavior. This establishes
real goal movement, not exact arrival or uninterrupted path following. Native
payloads then stop following and clear the patrol anchor, and the reconnected
owner opens the original guard's equipment menu.

The [validation manifest](guard-restart-validation-2026-10-23.json) records:

| Acceptance | Result |
|---|---|
| Native seed / clean restart, NeoForge 21.1.248 | 12 + 31 checks |
| Native seed / clean restart, NeoForge 21.1.233 | 12 + 31 checks |
| Fresh original coexistence regression | 41 checks on each version |
| Completed-fixture launch rejection | Four rejections; seven protected files unchanged, including two launches skipping preparation |
| Preparation rejection | Outside-artifact path and completed restart fixture reject |
| Stopped native persistence | Five worlds exclusively stopped; final entity-region NBT and NPC YAML independently match both fixtures |
| Production provenance | 1,020 Citizens and 74 Interactions classes per target match both actual native builds; six ordinary archives retain their hashes |

There are **168 fresh native assertions**, plus the six expected-negative fixture
checks. Native region-file decoding independently confirms both original guard
UUIDs, owner UUIDs, names, mode flags, cleared anchor and every inventory slot in
the final stopped worlds. A stopped seed image retained for 21.1.233 also confirms
the original follow/patrol modes and anchor before recovery. The 21.1.248 seed is
established by its native save/restart assertions and retained snapshots/logs;
no separately archived seed-world image is claimed for that fixture. Stopped
Citizens YAML independently retains the original five identities, professions,
AI settings and packet trait. Native entities remain Guard Villagers' ownership.

The opt-in guard init script now accepts artifact-confined fixture/build paths,
requires the project cache outside build outputs and checks marker/port/world/
phase state directly before launch, including when prepareServerRun is cached or
skipped. Completed restart worlds reject both reruns and coexistence cleanup.
Preparation validates the existing marker/world and stopped ownership and preserves
completed restart worlds. Historical fixtures and the deferred BenLi06 client
fixture remain unchanged. Guard provider and audit sources were frozen across
their respective runs.

Run from PowerShell 7 with Java 21. Prepare a fresh fixture with
`tools/prepare-guard-audit.ps1 -FixtureDirectory artifacts/your-guard-fixture`.
From `neoforge`, invoke the wrapper with:

```powershell
$arguments = @(
  '--project-cache-dir', '../artifacts/your-guard-cache',
  '-I', '../tools/guard-runtime-audit.gradle',
  '-Pneo_version=21.1.248',
  '-PguardAuditFixture=../artifacts/your-guard-fixture',
  '-PguardAuditBuildDir=../artifacts/your-guard-build',
  '-PguardAuditPhase=restart-seed',
  'runServer', '--console=plain'
)
./gradlew.bat @arguments
```

After successful stop, change only the phase to restart-recover. Use a separate
fresh fixture/build/cache for 21.1.233. The original audit remains available as
phase coexist, using another fixture. Keep loopback port 25630 and guard-world.
The dedicated source directory is opt-in and excluded from ordinary release JARs.

Existing 825 ordinary tests per target and 100 Interactions tests are explicitly
reused for unchanged production/dialogue/test/build sources; their original logs,
reports and archives are hash-verified. No fresh ordinary test run is claimed.
An initial probe compile used a private API, failed before launch and was corrected
to the public registry save method; its rejection log is retained.

Physical-client authentication/input/rendering, combined target-pack behavior,
arbitrary third-party AI overrides, unflushed hard termination and integrated
migration/release remain separate gates. The server payload checks exercise the
native server contract with a synthetic connection. They do not establish client
screen input or VR acceptance. No production restart/deployment or changes to the
deferred client acceptance fixture are included. The full goal remains active.
