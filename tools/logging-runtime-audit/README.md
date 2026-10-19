# NeoProtect / GriefLogger native audit

Use PowerShell 7 and Java 21. Prepare the pinned providers with
`tools/prepare-logging-audit.ps1`, then run from the repository root:

```powershell
./neoforge/gradlew.bat -p neoforge -I ../tools/logging-runtime-audit.gradle '-Pneo_version=21.1.248' runServer --console=plain
./neoforge/gradlew.bat -p neoforge -I ../tools/logging-runtime-audit.gradle '-Pneo_version=21.1.248' '-PloggingAuditPhase=recover' runServer --console=plain
```

The native phase creates fresh synthetic actors and exercises item placement,
door multi-placement, chest use/clicks, entity damage, a placement/break burst,
container/block rollback and a finite Economy coordinate-shop trade. Recovery
uses the exact saved actor, operation and transaction after a real clean server
restart. Existing fixture histories are retained; assertions filter by actor UUID.

The fixture is `artifacts/logging-audit-server`, loopback port 25632, with an
explicit marker. It contains NeoProtect 1.0.4, repaired GriefLogger 1.2.10,
Architectury 13.0.11, Config Lib 1.1.8, Create performance-v6 and Economy 0.5.6.
Economy requires NeoForge 21.1.247+, so this combined fixture uses 21.1.248.
The NeoForge 21.1.233 build and absent-provider regression remain separate.

The init script adds the probe only to the dialogue source set. Run a standard
`clean test build` afterward and inspect release JARs to exclude the probe and
provider classes. Logs and synthetic databases stay under ignored `artifacts/`.

This covers bounded burst retention and clean restart, not queue overflow,
process-kill durability, timed history expiry, unrestricted world/financial
rollback, production performance or physical VR/non-VR clients.
