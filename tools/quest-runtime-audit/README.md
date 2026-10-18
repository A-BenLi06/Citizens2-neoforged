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

The expanded fixture includes RSItemEditor 1.0.1, Kotlin for Forge 5.12.0 and
Paradigm 2.5.0, with the actual `paradigm:internal` NeoForge permission handler
selected in its isolated config. The combined audit passes 31 checks, including
shared/personal ownership, complete snapshots, permission revocation, explicit
quantity grants and ordinary/packet NPC commands. It creates synthetic item
definitions and permissions only in this fixture.
