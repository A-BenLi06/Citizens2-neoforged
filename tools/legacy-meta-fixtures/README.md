# Original Bukkit stream fixtures

`GenerateFixtures.java` sends synthetic, documented ItemMeta maps through the **actual original BukkitObjectOutputStream**. It uses Bukkit's own `Wrapper`, alias resolution and the selected Guava implementation. It does not start an old server or instantiate CraftMetaItem. The maps follow the inspected Arclight 1.20.1 CraftMetaItem serializer; this distinguishes writer-format acceptance from a complete old-server migration test.

The retained small fixtures in `neoforge/src/test/resources/legacy-meta` cover ordinary metadata, enchantment books, old section-sign text, hex colors, links, unknown/invalid fields, collection references and a cycle. `rich-guava25` was written with Guava 25.1-jre; the others use 31.1-jre. The large modified-UTF case stays under ignored artifacts and verifies 60,000 UTF-16 code units, including NUL and surrogate pairs, against the full expected string.

The subtype follow-up adds thirteen samples for armor dye/trim, writable/signed/knowledge books, potion contents/defaults, and retained invalid effect/trim/book data. Outer metadata is still synthetic; nested color and normal potion-effect values are actual original Bukkit `Color` and `PotionEffect` objects. The `EffectFields` DTO supplies an explicit `PotionEffect` alias for missing optional flags and malformed/unknown numeric identities. These samples establish the original nested wrapper schemas without claiming a running old CraftMetaItem implementation. Guava 31 outputs are retained; existing fixtures were not overwritten.

Recreate using paths to compatible original jars, JDK 21 and an output directory under this workspace:

```powershell
$writerClasspath = "$bukkitJar;$guavaJar"
New-Item -ItemType Directory -Force artifacts/legacy-meta-fixture-classes | Out-Null
& "$jdk/bin/javac.exe" -cp $writerClasspath -d artifacts/legacy-meta-fixture-classes tools/legacy-meta-fixtures/GenerateFixtures.java neoforge/src/main/java/net/citizensnpcs/api/util/LegacyBukkitData.java tools/legacy-meta-fixtures/ReadFixture.java
& "$jdk/bin/java.exe" -cp "artifacts/legacy-meta-fixture-classes;$writerClasspath" GenerateFixtures artifacts/legacy-meta-fixtures
$cases = @(Get-ChildItem artifacts/legacy-meta-fixtures -Filter '*.base64' | ForEach-Object { $_.FullName })
& "$jdk/bin/java.exe" -cp artifacts/legacy-meta-fixture-classes net.citizensnpcs.api.util.ReadFixture @cases
```

Repeat generation in a different output directory with Guava 25.1-jre to verify its older array field descriptors. Map iteration order may differ between writer JVMs; compare decoded content rather than assuming newly generated binary hashes match. The exact original writer jars and retained fixture hashes are recorded in `artifacts/bukkit-meta-validation-summary.json`.

Subtype evidence, including the expanded 97-check live metadata fixture, is recorded in `artifacts/bukkit-types-validation-summary.json` and `docs/bukkit-item-subtypes-parity-2026-09-22.md`. The earlier eleven-case standalone stream results are historical evidence; the subtype follow-up exercises its new retained samples through unit tests and the native server fixture.

The firework follow-up adds nine stream samples covering rockets, stars, empty metadata and invalid powers/counts/colors/shapes/fields. Valid nested values are original Bukkit `FireworkEffect` and `Color` objects; the actual serialized effect alias is `Firework`. `FireworkFields` uses that same explicit alias only for malformed samples. The three records in `structured-fireworks.yml` separately exercise the old Citizens map reader. All five shapes map by identity: the native and Bukkit enum orders differ for BURST/CREEPER. Evidence is in `artifacts/fireworks-validation-summary.json` and `docs/firework-item-metadata-parity-2026-09-22.md`.

No Bukkit or Guava compatibility shims are shipped. The production decoder parses bounded data records and never calls Java object deserialization, loads a named stream class, substitutes a class descriptor, or executes a stream object's callbacks. Unsupported graph shapes and metadata fields remain unavailable through the existing retention API.

## Older structured records and potion mappings

`structured.yml` has 21 synthetic DataKey records matching the original Citizens 2.0.32 reader. These are YAML maps, not Java serialization streams, and do not go through `GenerateFixtures` or `ReadFixture`. Unit and live-server tests cover their native conversion or retained unavailability. The original NPC save has no metadata-bearing records, so these are not production extraction samples.

`GeneratePotionMappings.java` invokes the actual original `PotionData` validation and `CraftPotionUtil.fromBukkit` offline, enumerating 43 valid potion combinations. It also extracts 33 original Bukkit effect constant names/IDs, independently checked against `CraftPotionEffectType.getName` bytecode. Regenerate with the original Arclight 1.20.1 and Guava 31.1-jre paths from the earlier instructions:

```powershell
& "$jdk/bin/javac.exe" -cp $writerClasspath -d artifacts/legacy-meta-fixture-classes tools/legacy-meta-fixtures/GeneratePotionMappings.java
& "$jdk/bin/java.exe" -cp "artifacts/legacy-meta-fixture-classes;$writerClasspath" GeneratePotionMappings artifacts/legacy-structured-mappings
```

The resulting `legacy-potion-data.properties` and `legacy-potion-effect-names.properties` correspond to the resource tables shipped under `neoforge/src/main/resources/citizens/`. Unknown/modded names and impossible combinations remain unavailable; source IDs never refer to modern registry positions. See `docs/structured-item-metadata-parity-2026-09-22.md` and `artifacts/structured-items-validation-summary.json` for current evidence. No old server is started by the generator.
