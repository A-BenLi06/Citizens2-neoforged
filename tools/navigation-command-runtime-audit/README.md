# Navigation command and movement audit

This opt-in audit uses a fresh loopback fixture at `127.0.0.1:25645`, with no
production data or physical clients. Use JDK 21 and wait for each native server
process to exit before launching another fixture. Ordinary build outputs must
remain separate from these audit builds.

From the repository root:

```powershell
./tools/prepare-navigation-command-audit.ps1 -Name navigation-command-new-server
Set-Location neoforge
./gradlew.bat -I ../tools/navigation-command-runtime-audit.gradle `
  '-Pneo_version=21.1.248' `
  '-PnavigationCommandBuildDir=../artifacts/navigation-command-new-build' `
  '-PnavigationCommandFixture=../artifacts/navigation-command-new-server' `
  --project-cache-dir ../artifacts/navigation-command-new-cache `
  --no-daemon --console=plain runServer
```

Choose new names for every attempt. Preparation rejects existing directories;
launch rejects prior server logs or completion markers before starting Java. Preserve failed
worlds/logs rather than repairing evidence in place.

The command probe uses actual Brigadier dispatch for ordinary and packet pig NPCs.
It checks rejected compound edits, numeric boundaries, non-finite/overflow values,
duration errors, strict unknown flags, permissions, complete valid edits, preserved
metadata and active request identity. Actual Citizens and vanilla walking must
reach destinations with teleport rescue disabled. Paused/idle virtual roots stay
unticked; active navigation receives native ticks, and real passengers tick once.
It records `complete.json` and then performs normal server shutdown.

For the existing label/mount regression probes, prepare a new fixture with
`-Regression interaction-label` or `-Regression packet-hologram` and launch the
same init script with the matching
`'-PnavigationCommandRegression=interaction-label'` or
`'-PnavigationCommandRegression=packet-hologram'`. Use separate fixture/build/cache
paths. These probes retain their own completion log markers and use synthetic
native player sessions, including actual packet codecs and tracking admission.

The retained original command reproduction uses
`navigationCommandOriginalSources` pointing to an artifact-confined preserved
main source directory; it expects the original partial-mutation assertion.
Original and failed gameplay is excluded from accepted check totals.

`python tools/record-navigation-command-validation.py` independently verifies the
dated accepted outputs without restarting them: unit XML, native markers, exact
saved YAML versus snapshots, arrival coordinates, native entity persistence,
production-class equality and separate release packages with audit code excluded.
See `docs/navigation-commands-2026-10-24.md` for the accepted paths and limits.
