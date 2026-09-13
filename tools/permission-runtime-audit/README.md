# Permission integration audit

This opt-in fixture exercises actual Citizens commands, shops, dialogue entry and the selected NeoForge permission handler. Its classes belong to the `interactions` test source set and are not included in normal release builds.

Prepare and run from the repository root / `neoforge` directory with JDK 21:

```powershell
.\tools\prepare-permission-audit.ps1 -ParadigmJar 'path/to/Paradigm-neoforge-1.21.1-2.4.2b.jar'
Set-Location neoforge
.\gradlew.bat '-Pneo_version=21.1.248' -I ../tools/permission-runtime-audit.gradle runServer --console=plain
# Repeat to verify the same provider's persistent grant after a full restart.
.\gradlew.bat '-Pneo_version=21.1.248' -I ../tools/permission-runtime-audit.gradle runServer --console=plain
.\gradlew.bat '-Pneo_version=21.1.248' '-Ppermission_audit_provider=false' -I ../tools/permission-runtime-audit.gradle runServer --console=plain
```

The preparation script writes only `artifacts/permission-audit-server` and `artifacts/permission-fallback-audit-server`, with loopback ports 25582 and 25583. It copies the provider jar without changing it; it never copies production player/permission data or credentials. Test subjects and groups are unique per run. A separate fixed UUID is the restart witness. Existing NPC data causes the fixture to refuse execution.

Coverage includes exact command and flag registration, wildcard identity, entity creation permissions, ownership, `--id`/`--uuid` selection, help aliases, flag aliases, explicit denial versus OP/source defaults, inherited groups, runtime/Unicode names, live world/dimension/server/network contexts, shops, dialogue entry, local attachment lifetime, late declarations and bridge lifecycle. It also runs without a permission provider.

Ordinary grants/revocations use Paradigm's actual administrative commands. Its 2.4.2b NeoForge `STRING` argument is implemented as Brigadier `word()`, rejecting wildcard and Unicode names even when quoted. The fixture therefore creates those particular rules through its public `PermissionsHandler` mutation methods. The boolean on these methods means **denied**, not allowed. No provider jar, private field or database is patched. This is not evidence that those names work through that provider version's CLI.

The fixture also checks world/dimension-scoped group membership, inherited contextual parents, revocation and actual temporary-group expiry. It continues ticking during the expiry wait.

The fixture queues a barrier on the provider's single storage executor after cleanup and continues ticking until it completes. Immediate shutdown after a burst of mutations can cancel pending provider jobs. The audit must not mistake a clean Minecraft exit for completed persistence. The Gradle gate rejects missing completion markers, explicit failures and runs over three minutes. A first successful run has 64 checks; a subsequent run has 65, including the saved grant. The fallback run has 17 checks.

After running opt-in probes, perform the normal build and verify that jars contain no `RuntimeAudit` classes:

```powershell
.\gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```
