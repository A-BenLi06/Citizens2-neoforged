# Follow runtime audit

Run from the repository root using PowerShell 7 and JDK 21:

```powershell
./tools/prepare-follow-audit.ps1
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/follow-runtime-audit.gradle runServer --console=plain
```

The opt-in source directory adds the fixture to the development server only. The server runs in `artifacts/follow-audit-server`, binds to `127.0.0.1:25615`, and requires its marker file. It installs a fixture permission handler, admits synthetic players with native protocol encoders, requests a destination chunk using a native ticket and waits for ticking readiness. No production player/NPC data is used.

The suite covers command validation, permission/ownership gates, pending name choices, persisted target identity, reconnection and virtual player UUIDs, exact navigation ownership, cancellation/completion/factory/stop callbacks, protection through native damage, real tick movement and world/virtual dimension transfers. It checks native admission and index membership, entity state, destination navigation, viewer cleanup, teleport cancellation and removal during callbacks. Read-only reflection observes PathNavigation's level.

Success requires `[FOLLOWAUDIT] COMPLETE 129 checks`, no `[FOLLOWAUDIT] FAILED`, and successful Gradle completion. The fixture stops its server and releases its native chunk ticket. Run Gradle invocations sequentially and wait for each process to exit. A clean normal build must exclude the audit classes from both release and source archives.

Physical clients, a complete installed modpack, proxy arrival and mounted cross-dimension hierarchies require separate acceptance. See [the implementation and validation record](../../docs/follow-and-dimension-transfer-2026-09-26.md).
