# Native template event persistence audit

Run from `neoforge/` with JDK 21. The probe is opt-in and excluded from ordinary
builds. It uses native Citizens events and synthetic actors, not physical input.

Prepare a fresh fixture from the repository root:

```powershell
./tools/prepare-template-events-audit.ps1 -Name template-events-fixed-233-server
```

Launch its seed process from `neoforge/` (quote the complete `-P` arguments in
PowerShell):

```powershell
./gradlew.bat -I ../tools/template-events-runtime-audit.gradle `
  '-PtemplateEventsBuildDir=../artifacts/template-events-native-233-build' `
  '-PtemplateEventsFixture=../artifacts/template-events-fixed-233-server' `
  --project-cache-dir ../artifacts/template-events-native-233-cache `
  --no-daemon --console=plain runServer
```

Seed covers ordinary and packet NPCs, the five supported event command types,
NPC cloning, strict snapshot preparation, registry reload and unsaved explicit
reapplication. It leaves the last changed definition to normal shutdown saving.
After the Java process exits successfully, preserve its log, `config/citizens/saves.yml`,
world `data/scoreboard.dat`, `expected-npcs.yml` and `expected.properties` before
the next launch. Set only `phase.txt` to `recover`, then run the same command.

Recovery checks the actual restored NPC UUIDs/names, scoreboard deltas from
spawn and all other events, no replay of application/YAML actions, and removal
of event bindings followed by another snapshot load. Both phases stop the
server normally. Completed phases reject replay before Java starts.

For NeoForge 21.1.248 use a separate fixture, output and cache, and supply
`'-Pneo_version=21.1.248'`. Do not share output directories with ordinary builds.

The original negative control uses a preserved `f48cf96` core Java source tree
under artifacts and the same probe. Supply `templateEventsOriginalSources` as a
quoted project property pointing to that tree. The task requires the specific
missing-event-snapshot assertion; it does not count that failed gameplay as
accepted coverage. Preserve the failed world and log.

No third-party provider fixture, production server or deferred client fixture
is used by this audit.
