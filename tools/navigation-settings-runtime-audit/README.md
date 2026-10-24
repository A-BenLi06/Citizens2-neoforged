# Navigation settings persistence audit

Use JDK 21. This opt-in core audit uses a new artifact-confined fixture at
`127.0.0.1:25646`, without production data or physical clients. Preserve every
attempt; do not restart accepted phases or edit failed worlds to pass them.

```powershell
./tools/prepare-navigation-settings-audit.ps1 -Name navigation-settings-new-server
Set-Location neoforge
./gradlew.bat -I ../tools/navigation-settings-runtime-audit.gradle `
  '-Pneo_version=21.1.248' `
  '-PnavigationSettingsBuildDir=../artifacts/navigation-settings-new-build' `
  '-PnavigationSettingsFixture=../artifacts/navigation-settings-new-server' `
  --project-cache-dir ../artifacts/navigation-settings-new-cache `
  --no-daemon --console=plain runServer
```

Wait for the actual process to exit successfully and release the fixture port.
Then prepare its explicit restart from the repository root:

```powershell
./tools/prepare-navigation-settings-audit.ps1 -Name navigation-settings-new-server -Phase recover
```

Repeat the same Gradle command for recovery. Preparation retains the stopped
seed save and log under `evidence/seed`. A phase admission marker prevents
replay before Java starts. The preflight runs again immediately before execution
even if Gradle caches or skips preparation. Use separate fixture/build/cache names for each
NeoForge target and negative control. Ordinary release outputs stay separate.

The seed uses real command dispatch on ordinary and packet pigs. It verifies
serialized attack range/delay and path distance margin, cloning, snapshot
restoration, registry reload, and actual walking with restored defaults.
Late unsaved command changes then test normal shutdown persistence. Recovery
checks the same identities/settings after a full JVM restart, drives another
real walk, resets settings through commands and verifies removal of stale
overrides through another reload. Existing navigation settings and persistent
metadata remain covered throughout. Signed attack delays retain compatibility.

`seed.complete.json` and `recover.complete.json` contain exact assertions and
snapshots. Successful runs emit `[NAVSETTINGSAUDIT] COMPLETE` and stop normally.
The original-source control uses `navigationSettingsOriginalSources` and expects
`ordinary_snapshot_contains_attack_settings` to fail before clone/reload work.

`python tools/record-navigation-settings-validation.py` inspects stopped saves,
unit XML, exact production classes, separate ordinary archives and the replay
guards. Synthetic native checks do not establish physical-client rendering or
complete Citizens acceptance. The dated acceptance report retains the initial
cached-preparation guard failure and distinguishes it from the corrected guards.
