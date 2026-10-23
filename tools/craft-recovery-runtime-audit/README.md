# Native craft recovery audits

These opt-in probes run actual result-slot clicks against isolated FTB worlds.
Ordinary archives never include them. Existing modes are seed/recover and bindings;
the party audit adds party-seed, party-recover and party-verify in three separate
server processes, in that order. It uses two native connected player sessions.

Prepare a fresh party fixture with PowerShell 7:

```powershell
./tools/prepare-craft-party-audit.ps1 -ProviderDirectory <pinned-provider-directory> -Fixture artifacts/<new-fixture>
```

The provider directory needs the four exact JAR names declared by the preparation
script. No production world, ledger or player data is used. Preparation refuses
an existing destination or a path outside artifacts.

From neoforge, set JAVA_HOME to a Java 21 JDK and run each phase separately:

```powershell
$arguments = @('--project-cache-dir', '../artifacts/<new-cache>',
    '-I', '../tools/craft-recovery-runtime-audit.gradle', '-Pneo_version=21.1.248',
    '-PcraftRecoveryBuildDir=../artifacts/<new-build>',
    '-PcraftRecoveryFixture=../artifacts/<new-fixture>')
./gradlew.bat @arguments -PcraftRecoveryPhase=party-seed runServer --console=plain
./gradlew.bat @arguments -PcraftRecoveryPhase=party-recover runServer --console=plain
./gradlew.bat @arguments -PcraftRecoveryPhase=party-verify runServer --console=plain
```

Use argument arrays so property values are passed as single arguments. Save each
phase's output before the next process replaces logs/latest.log. Use another fresh
fixture/build/cache for 21.1.233. Runs sharing localhost port 25646 are sequential.
Each phase shuts down itself. Confirm successful terminal completion and exclusive
stopped world/journal ownership before copying native save evidence.

Fixture identity, loopback properties and party phase prerequisites are checked
both during preparation and directly before launch, including skipped preparation.
A completed party phase cannot run again against the same fixture. Retain failed
worlds and start a fresh fixture rather than overwriting their evidence.

The workflow verifies two contributors, leave during consumption, offline original
party recovery, reconnect, personal progress, native rejoin, two repeat resets,
original-notification replay and a second saved-world restart. It does not claim
physical network input, Economy payments or arbitrary backup/device-loss recovery.
FTB CustomTask maxima are runtime owner configuration. The seed writes its owned
task ID/maximum definition once. Subsequent processes register the original
definition through CustomTaskEvent before FTB loads the quest file, and assert
that the native initialization event ran exactly once. Recovery never silently
replaces an absent task owner with the journal's expected maximum.
See docs/ftb-craft-party-recovery-2026-10-23.md for recorded acceptance.
