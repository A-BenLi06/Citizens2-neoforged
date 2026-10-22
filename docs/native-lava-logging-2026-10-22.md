# Native lava ignition logging

Date: 2026-10-22, UTC+08:00. Follow-up to
[native fire logging](native-fire-logging-2026-10-22.md), advancing ADAPT-09/11.

Both native `LavaFluid.randomTick` branches created actual fire while NeoProtect
1.0.4 returned no history. They use `Level.setBlockAndUpdate` after NeoForge's
`FluidPlaceBlockEvent`; they do not execute the vanilla fire-tick methods covered
by the preceding correction. The failing reproduction is retained separately
from the successful runs.

Citizens now observes those two writes after the native fluid event has selected
the resulting state. Only a selected `BaseFireBlock` enters the existing optional
fire observation bridge. The original world mutation, return and exception remain
native. NeoProtect owns configuration, cause inheritance, snapshots and storage;
the earlier provider-specific state correction is reused. No additional event is
published to the global bus. A replacement with another block type does not create
an invented fire record. Absent NeoProtect resolves no provider classes.

The pinned NeoForge `EventHooks.fireFluidPlaceBlockEvent` returns `getNewState()`
without checking the canceled flag. Flag-only cancellation therefore still
ignites fire in this version. Selecting air prevents ignition; selecting stone
places stone. The audit checks these actual native behaviors in both branches.
This logging adapter does not alter the helper's cancellation semantics.

The fixture uses deterministic random choices to enter both native branches,
actual lava blocks and native fluid events. No synthetic placement event is
posted to manufacture acceptance. Its natural lava sources retain NeoProtect's
unknown actor, one air-to-fire record per ignition and the native placement action.
It also checks disabled tracking, event-selected non-fire/air states, the flag-only
case, clean restart using original positions and a run without NeoProtect.
Player-bucket causal attribution and arbitrary mod-specific ignition are separate
requirements; no player identity is guessed for natural lava.

Exact final source/provider/log/package hashes and results are in
[the validation record](native-lava-logging-validation-2026-10-22.json).
The final lava fixture passes 22 native, seven restart and three provider-absent
checks. The preceding environmental regression passes 30 native, 24 restart and
six provider-absent checks. Ordinary builds pass all 775 tests on each NeoForge
target; the separate Interactions suite passes all 100. Six retained ordinary
source/binary archives contain the new hook and exclude runtime audits/foreign
providers while preserving separate Citizens and Interactions ownership.

Build output and Gradle project caches are isolated from ordinary builds. The
preceding fire/TNT fixture is rerun with this production source. Its earlier
process-kill evidence remains attributed to its recorded source revision rather
than being presented as a new kill of this lava revision.

Reproduce with PowerShell 7 / Java 21:

```powershell
./tools/prepare-logging-audit.ps1 -FixtureDirectory artifacts/lava-logging-audit-server -ServerPort 25644 -WorldName lava-world
./neoforge/gradlew.bat -p neoforge --project-cache-dir ../artifacts/lava-logging-cache -I ../tools/lava-logging-runtime-audit.gradle '-Pneo_version=21.1.248' runServer --console=plain
./neoforge/gradlew.bat -p neoforge --project-cache-dir ../artifacts/lava-logging-cache -I ../tools/lava-logging-runtime-audit.gradle '-Pneo_version=21.1.248' '-PlavaAuditPhase=recover' runServer --console=plain
```

The absent phase uses a separate prepared fixture, port 25645 / `lava-absent-world`,
with NeoProtect moved from `mods` to `compile-libs`. Do not prepare that fixture
again without moving the provider out again. Production and the pinned physical
client fixture are unchanged. Provider write failures, other event-observer
coexistence, queue/retention/load, legacy provenance and physical clients remain
open release gates.
