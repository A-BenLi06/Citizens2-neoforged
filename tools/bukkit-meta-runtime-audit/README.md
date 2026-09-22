# Bukkit metadata runtime audit

The opt-in fixture runs only in `artifacts/bukkit-meta-audit-server`, on loopback port 25590, with its own `metadata-world`. It requires the marker written by the preparation script and an empty default NPC registry. Test NPCs use a separate in-memory named registry; `migrated.yml` contains only test data.

Use JDK 21 and run Gradle sequentially:

```powershell
./tools/prepare-bukkit-meta-audit.ps1
Set-Location neoforge
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/bukkit-meta-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/removal-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The metadata fixture verifies 36 checks: both original Guava stream schemas, exact JSON name/lore/numeric metadata, live enchantment holders and tooltip flags, enchanted-book storage, explicit edits, native encoding, actual NPC spawn/despawn, YAML persistence, NPC copy, shop reward/payment, and retained unsupported/invalid/cyclic records. `[BUKKITMETAAUDIT] COMPLETE` is required and its failure marker rejects the Gradle run. Each fixture launch has a four-minute task timeout.

See `tools/legacy-meta-fixtures/README.md` for sample provenance. These are synthetic maps passed through the real original writer, not metadata extracted from the production NPC save. They do not establish special ItemMeta subtype, opaque internal NBT, physical-client or full-modpack acceptance. Normal release/source jars must exclude all runtime probes.
