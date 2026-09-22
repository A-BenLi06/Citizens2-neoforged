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

The metadata fixture verifies 97 checks: both original Guava stream schemas, exact JSON name/lore/numeric metadata, live enchantment holders and tooltip flags, enchanted-book storage, explicit edits, native encoding, actual NPC spawn/despawn, YAML persistence, NPC copy, shop reward/payment, and retained unsupported/invalid/cyclic records. Subtype checks cover leather/horse armor, native trim holders and dye/trim tooltips, writable/signed/knowledge books, all four potion-bearing item forms, actual NPC armor/copies, potion use on a living entity and a NeoForge FakePlayer, native arrow color and signed-book resolution. Invalid effect IDs/levels, missing trim, oversized pages, latent writable-book fields and wrong-item metadata retain their source definitions. `[BUKKITMETAAUDIT] COMPLETE` is required and its failure marker rejects the Gradle run. Each fixture launch has a four-minute task timeout.

Potion use on a non-player applies effects without consuming its stack; the separate player case verifies consumption and the returned glass bottle. The arrow case supplies an actual bow as the firing weapon. These use native behavior, with no production changes to satisfy fixture assumptions.

See `tools/legacy-meta-fixtures/README.md` for sample provenance. These are synthetic outer maps passed through the real original writer; nested colors/effects use original Bukkit objects. They are not metadata extracted from the production NPC save. Uncovered ItemMeta subtypes, opaque internal NBT, physical-client and full-modpack acceptance remain open. Normal release/source jars must exclude all runtime probes.
