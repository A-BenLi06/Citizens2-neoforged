# Native logging recovery after forced JVM termination

Date: 2026-10-22, UTC+08:00. Advances ADAPT-09/11.

The isolated NeoForge 21.1.248 fixture runs the existing native NeoProtect,
GriefLogger and Economy workflow, flushes NeoProtect, verifies both histories and
the original financial receipt, and explicitly saves player/world state. It forces
the fixture identity file before announcing a run-specific PID/actor/operation
marker. The PowerShell runner then forcibly terminates that fresh JVM. Shutdown
handlers do not run at the kill point.

The replacement JVM loads the same fixture and original actor, verifies both
independent log owners, and queries Economy through its supported commerce API.
All 37 native checks before termination and eight restart checks pass:

- Exactly one original transaction, the settled balance and delivered goods survive
  the shop's world rollback and subsequent process kill.
- Both providers retain the actor's modded placement and all 96 placements plus
  96 breaks in the burst. The providers' native databases remain independent.
- GriefLogger retains original container quantities/name components, and the saved
  world retains the matching rolled-back container contents.

After successful recovery/shutdown, read-only SQLite `quick_check` returns `ok`
for GriefLogger and Economy. NeoProtect uses H2; its recovered native lookup and
complete history assertions are the evidence here, rather than a SQLite check.
Exact source/provider/state/termination/log hashes are in
[logging-crash-validation-2026-10-22.json](logging-crash-validation-2026-10-22.json).

The runner isolates both Gradle build and project cache, validates the current run
token against saved actor/operation, and checks the Java process's exact isolated
argument-file path and creation time before termination. It retains killed and
recovered logs separately. The fixture marker, loopback address and port 25632
remain mandatory. Production is not deployed or restarted.

This verifies a real process kill **after observed flush and explicit world/player
saves**. It does not establish that queued/uncommitted events survive termination,
that all databases/world files commit atomically, or that a logger can reverse
money. Queue overflow, failures during writes, timed retention tooling, broader
entity/fire/explosion attribution, production load and physical clients remain
required acceptance. Complete matching backups still belong to their native owners.
