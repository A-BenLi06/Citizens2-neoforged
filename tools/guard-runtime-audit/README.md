# Native Guard Villagers fixture

Prepare the isolated fixture from the pinned Guard JAR, then run one Gradle
invocation at a time with Java 21:

```powershell
./tools/prepare-guard-audit.ps1
./neoforge/gradlew.bat -p neoforge -Pneo_version=21.1.248 -I ../tools/guard-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge -Pneo_version=21.1.233 -I ../tools/guard-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge test build --console=plain
```

The runtime probe is an opt-in main source directory. Restore the ordinary build
afterward and inspect release JARs to confirm probe exclusion. The init script
validates the fixture marker, loopback port and world before launching. A failed
or incomplete assertion fails Gradle. The reproduction flag is historical and
requires pre-adapter sources; it is not an acceptance mode for current sources.

For isolated outputs, pass artifact-confined guardAuditFixture and guardAuditBuildDir
with a separate --project-cache-dir outside build outputs. Preparation accepts
FixtureDirectory. Native restart uses phases restart-seed then restart-recover;
the default phase coexist retains the original audit. Completed restart fixtures
reject reruns, coexistence cleanup and re-preparation. Marker, port 25630 and
guard-world guards also run directly before launch when preparation is cached or
skipped. See [the restart report](../../docs/guard-restart-2026-10-23.md) for the
argument-array invocation, 168 native assertions and stopped persistence evidence.
Historical and deferred client worlds remain separate.

See `docs/guard-villagers-coexistence-2026-10-18.md` for the supported boundary
and limitations. No production data or live server is involved.
