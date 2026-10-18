# Native FTB task audit

Run `tools/prepare-quest-audit.ps1` with the pinned local provider artifacts, then:

```powershell
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/quest-runtime-audit.gradle runServer --console=plain
```

The fixture runs only at loopback port 25628 in `artifacts/quest-audit-server`.
It creates synthetic players, a chapter, tasks and a party using public native
provider APIs. No production quest/player data is copied. Fixture sources enter
the dialogue source set only through the init script and must not ship in release
jars. Ordinary test/build restores the separate Citizens and Interactions jars.
