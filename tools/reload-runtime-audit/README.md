# Default registry reload audit

Run from the repository root in PowerShell 7 with JDK 21:

```powershell
./tools/prepare-reload-audit.ps1
./neoforge/gradlew.bat -p neoforge -I ../tools/reload-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/reload-runtime-audit.gradle runServer --console=plain
```

Run servers sequentially. The synthetic fixture binds to `127.0.0.1:25639`, uses only
`artifacts/reload-audit-server`, and stops itself. Confirm the port is available first.
Do not copy production data into it. Fixture code is included only through the opt-in
initialization script; rebuild normally before distributing release archives.

The audit injects provider failure after partial restoration, including a spawned
candidate whose trait cleanup throws. It checks old identity/entity preservation,
candidate disposal and suppressed cleanup errors, successful registry publication,
forced retirement despite despawn vetoes, unregistered trait listeners, delayed spawn
cancellation, ordinary and packet transport, another registry's queued respawn with
the same UUID/ID, and cleanup failure after publication without record deletion.

Expected result: `[RELOADAUDIT] COMPLETE 30 checks` and `BUILD SUCCESSFUL`.
The original implementation at `deb5021` fails `failed_reload_preserves_identity`.
The fixture does not accept shutdown, configuration/shop transactionality, or arbitrary
external provider side effects as verified.
