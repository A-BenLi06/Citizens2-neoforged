# Native command dimensions and create validation

Minecraft 1.21.1 / NeoForge 21.1.248. Scope follows [the ecosystem guide](neoforge-ecosystem-replacements-2026-09-22.md).

Reviewed: 2026-09-25 11:15:19 +08:00 (UTC+08:00).

The packaged MiniMessage smoke exposed an independent command defect: `/npc create ... --at 0,-60,0,minecraft:overworld` split the dimension's namespace at its colon and reported an invalid number. Creation had already registered the NPC, leaving an unspawned entry. The dedicated negative control also reproduces rejection of a namespaced location with rotation.

## Coordinate contract

The syntax parser now separates coordinate fields from live world resolution. Native coordinates use `x,y,z[,world[,yaw[,pitch]]]`, so `minecraft:the_nether` or another provider's complete dimension ID remains one field. Omitted worlds use the actual command source level. The older all-colon form remains available for bare world names; mixed separators are rejected. Empty fields are not collapsed, and coordinates/angles must be finite.

Denizen forms retain `l@x,y,z`, `l@x,y,z,world`, `l@x,y,z,yaw,pitch`, and `l@x,y,z,yaw,pitch,w@world`. The world prefix is stripped only at the start of that field. A comma form supports namespaced worlds in these forms as well.

Explicit world names use the existing strict native resolver: loaded dimension IDs, unambiguous bare paths and known world-folder/legacy aliases are available. An unknown command world does not invoke the save-migration overworld fallback, and `absent:overworld` cannot match `minecraft:overworld` by path alone. Save migration itself is unchanged.

`/npc create` resolves its explicit `--at` or inherited `--location`/`--entitylocation` before creating a named registry, registering an NPC, applying traits/templates or selecting the new NPC. Invalid location input preserves existing NPCs and selection. This is input preflight; arbitrary template failures or cancelled spawns do not gain a general transaction mechanism.

## Validation

The isolated fixture on loopback port 25609 passes 49 checks. It uses actual loaded levels and the real dispatcher, checks exact spawned entity coordinates/rotation, both coordinate orders, source-relative world defaults, known folder aliases, home-command storage, malformed/unavailable inputs, unchanged NPC identity lists/selection and absent named registries after invalid creation. Six ordinary grammar tests cover native IDs including numeric paths, defaults, legacy forms, scientific numbers, empty fields and nonfinite values.

```powershell
./tools/prepare-command-location-audit.ps1
$env:JAVA_HOME = 'C:\Users\ben_l\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/command-location-runtime-audit.gradle runServer --console=plain
```

Sequential regression passes 62 removal checks, 107 NPC assertions and 223 general probes. All 232 ordinary tests pass with zero failures/errors/skips. Normal build and binary/source audit exclusions pass, including the new syntax parser in both artifacts. The ordinary bootstrap smoke passes with the original complete `minecraft:overworld` argument, checking that the newly created NPC ID is listed as a spawned cow before clean shutdown.

Citizens SHA-256: `24a3b27f3fd0ad4388db54258949ce2cac24b20e0053c4e918a41b293dd5f130`; sources: `ada929ad89d874f8f5ec23210948f3c6f3560ad4326439b43dbcc98079c9d5ce`. The separate Interactions jar and original Citizens save retain their previous hashes.

Evidence is consolidated in `artifacts/command-location-validation-summary.json`, including the negative control and earlier packaged reproduction. Physical-client and combined modpack/proxy acceptance remain separate work. No production data, retained provider or default NeoForge version changes, and no deployment occurs.
