# Actual FTB XMod / Rhino fixture

Prepare the pinned providers, including Economy 0.5.9 from its source build:

```powershell
./tools/prepare-xmod-audit.ps1 -EconomyJar <absolute-provider-jar>
./neoforge/gradlew.bat -p neoforge --project-cache-dir ../artifacts/xmod-audit-gradle-cache -Pneo_version=21.1.248 -PxmodAuditBuildDir=../artifacts/xmod-audit-build -I ../tools/xmod-runtime-audit.gradle runServer --console=plain
./neoforge/gradlew.bat -p neoforge test build --console=plain
```

Run Gradle sequentially. The fixture uses only loopback port 25631 and synthetic
world/config/player/quest data. `citizens_xmod_audit.js` is copied into its
server_scripts directory. The probe records actual Rhino callbacks; native
result-slot consumption and decoded reward messages cause the events. Restoring
the standard build excludes all probe classes/script files from release JARs.
The selected JEI requires NeoForge 21.1.238 and Economy requires 21.1.247;
the complete fixture cannot start on Citizens' 21.1.233 baseline. Use the selected
21.1.248 acceptance target for native runs and the ordinary build for baseline
compatibility. Provider metadata is not rewritten to bypass those requirements.

The two-member reward fixture also checks native party joins/leaves, shared and
individual ownership, offline settlement, new/retired sessions and repeat cycles.
See `docs/ftb-party-reward-acceptance-2026-10-22.md`. Its 19 checks run alongside
the 45 XMod and 27 commerce checks. The monetary controls verify that unavailable
inspection holds repeat admission, while a real frozen account permits historical
inspection and leaves the new native ACCEPTED credit pending for idempotent recovery.
Both buildDirectory and project cache
must be isolated when using a separate audit build; changing only the output path
can let Gradle's stale-output cleanup delete another build's classes.

See `docs/kubejs-ftb-script-contracts-2026-10-18.md` for supported APIs and limits.

The PREPARED commerce fixture disconnects an original buyer after a deliberately
missed native commit event. It verifies exact receipt discovery into the original
team despite a later party change, actual pending delivery, a failed native commit,
missing/older provider handling and repeated console reconciliation. See
`docs/commerce-prepared-recovery-2026-10-22.md` for the supported boundary and results.
