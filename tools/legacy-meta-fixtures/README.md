# Original Bukkit stream fixtures

`GenerateFixtures.java` sends synthetic, documented ItemMeta maps through the **actual original BukkitObjectOutputStream**. It uses Bukkit's own `Wrapper`, alias resolution and the selected Guava implementation. It does not start an old server or instantiate CraftMetaItem. The maps follow the inspected Arclight 1.20.1 CraftMetaItem serializer; this distinguishes writer-format acceptance from a complete old-server migration test.

The retained small fixtures in `neoforge/src/test/resources/legacy-meta` cover ordinary metadata, enchantment books, old section-sign text, hex colors, links, unknown/invalid fields, collection references and a cycle. `rich-guava25` was written with Guava 25.1-jre; the others use 31.1-jre. The large modified-UTF case stays under ignored artifacts and verifies 60,000 UTF-16 code units, including NUL and surrogate pairs, against the full expected string.

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

No Bukkit or Guava compatibility shims are shipped. The production decoder parses bounded data records and never calls Java object deserialization, loads a named stream class, substitutes a class descriptor, or executes a stream object's callbacks. Unsupported graph shapes and metadata fields remain unavailable through the existing retention API.
