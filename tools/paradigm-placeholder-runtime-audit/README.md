# Paradigm NPC placeholder audit

The fixtures use loopback ports 25598 (actual Paradigm) and 25599 (provider absent), separate directories below `artifacts/` and their own `placeholder-world`. Test NPCs use a named in-memory registry. No production world, player or permission data is copied.

From the repository root, supply the retained provider jar and run with JDK 21. Wait for each Gradle process to finish before starting the next.

```powershell
./tools/prepare-paradigm-placeholder-audit.ps1 -ParadigmJar 'path/to/Paradigm-neoforge-1.21.1-2.4.2b.jar'
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/paradigm-placeholder-runtime-audit.gradle runServer --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Provider audit failed' }
./gradlew.bat '-Pneo_version=21.1.248' '-Pplaceholder_audit_provider=false' -I ../tools/paradigm-placeholder-runtime-audit.gradle runServer --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Fallback audit failed' }
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Test/build failed' }
```

The provider fixture passes 72 checks; the provider-absent fixture passes 8. Two synthetic players use native PlayerList admission, protocol encoders and chunk acknowledgements. The real provider registers all four keys and delivers formatted messages through its public MessageService. Tests cover independent selections, names/IDs/UUIDs, no selection, nearest query/range/dimension behavior, despawn/destruction, worker-thread snapshots, console/offline contexts, duplicate/conflicting registrations, partial rollback, unregister/re-register, other-owner preservation and actual shutdown cleanup.

For the replacement case, the audit publishes another real Paradigm API provider backed by the real running Services, using the provider registry's lifecycle method. It never changes Citizens' private registration state. Read-only reflection observes its owned handles; the actual provider's external formatter exercises worker callbacks. This is an API lifecycle test, not full provider reload acceptance. Completion is reported after native server shutdown, so a cleanup failure cannot masquerade as a passed run.

The first isolated provider launch may emit Paradigm's own `commands.json` write warning; it does not establish that general command configuration has passed acceptance. The intentionally occupied Citizens key emits an expected registration warning during collision testing. Any fixture failure marker, missing completion marker or three-minute Gradle timeout fails the audit. Release/source jars must exclude fixture classes and the Paradigm jar/API classes.
