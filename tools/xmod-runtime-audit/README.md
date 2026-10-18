# Actual FTB XMod / Rhino fixture

Prepare the pinned providers, including Economy 0.5.5 from its source build:

```powershell
./tools/prepare-xmod-audit.ps1 -EconomyJar <absolute-provider-jar>
./neoforge/gradlew.bat -p neoforge -Pneo_version=21.1.248 -I ../tools/xmod-runtime-audit.gradle runServer --console=plain
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

See `docs/kubejs-ftb-script-contracts-2026-10-18.md` for supported APIs and limits.
