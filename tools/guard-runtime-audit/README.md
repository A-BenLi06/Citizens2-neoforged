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

See `docs/guard-villagers-coexistence-2026-10-18.md` for the supported boundary
and limitations. No production data or live server is involved.
