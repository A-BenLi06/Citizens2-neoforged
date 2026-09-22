# Bukkit ItemMeta stream migration

Implementation base: `e3533c7`. Target: Minecraft 1.21.1 / NeoForge 21.1.248.

## Source format and native conversion

Citizens 2.0.32 writes `meta.encoded-meta`; newer versions also use scalar `meta`. The Base64 bytes form a Java serialization stream containing `org.bukkit.util.io.Wrapper` around ItemMeta's serialized map. The wrapper adds the `==: ItemMeta` alias. Guava immutable maps/lists use serialized forms, including an inherited immutable bimap form for a one-entry map. This is distinct from ItemEdit's gzip NBT payload.

`LegacyBukkitData` reads this bounded data format directly. It understands references, supported class field layouts, primitive values, arrays, Guava map/list forms and the JDK map/list custom data used by these records. No named stream class is loaded or instantiated; no `ObjectInputStream` callbacks or replacement class descriptors run. Byte, reference, collection and nesting limits, cycle detection, duplicate-key checks and trailing-data rejection prevent ambiguous or incomplete results. Old Guava array descriptors and newer object-typed descriptors yield the same plain data.

`LegacyBukkitMeta` converts recognized `UNSPECIFIC`, base `ARMOR` and `ENCHANTED` records. It restores JSON names/lore, legacy section-sign colors/hex/reset and URL clicks, custom model data, damage, repair cost, unbreakability, normal enchantments, stored book enchantments and supported tooltip flags. Legacy text keeps ampersands and Citizens markup literal. Formatting resets explicitly clear decorations. Legacy plain-text newlines are retained rather than reproducing CraftChatMessage's first-line truncation.

Historical renamed Bukkit enchantment names live in a resource table; other names resolve against native registries. Unknown registry entries, duplicate aliases, invalid native component values and unsupported fields reject the whole item. Enchanted-book metadata requires an enchanted book. Native component codecs validate complete converted stacks before they become usable. A successful owner save writes the ordinary native component representation.

The editable name/lore view does not override metadata unless `edited` is true. Edits apply after complete conversion and their flag is consumed only on success. A registered legacy reader remains authoritative; returning false or throwing preserves unavailability, while setting the reader to null restores the built-in decoder. Existing owner-local retention preserves definitions the decoder cannot yet handle.

## Evidence and remaining work

Fixtures use the actual original Arclight Bukkit output stream with synthetic serializer maps, and Guava 31.1-jre / 25.1-jre. This establishes the wrapper and collection format without claiming those samples came from a production NPC. The original Citizens save remains unchanged and contains only six plain item records, with no serialized metadata. Source bytecode and writer hashes are retained under ignored artifacts.

The isolated runtime fixture verifies 36 checks against live enchantment registries, native item codecs, actual NPC spawn/despawn, saved YAML, independent NPC copies and shop transactions. Nine new unit cases verify both Guava schemas, old text, explicit edits, malformed/cyclic data, retention and extension-reader behavior. All 32 focused item tests and 191 ordinary tests pass, with zero failures/errors/skips. Final sequential validation also passes 62 removal checks, 107 NPC assertions and 223 general probes. Eleven standalone stream cases include exact long modified UTF with NUL and supplementary Unicode.

Normal build succeeds. Release/source jars include the migration classes and alias resource and exclude all runtime probes and test fixtures. Citizens jar SHA-256: `7dce128d1f54b977fe141fb1f6c1bc1bdd86f9e4c54a18a8874eca2f3aac97ae`. Logs and source/writer/fixture/jar hashes are recorded in `artifacts/bukkit-meta-validation-summary.json` and `walkthrough.md`. Unrelated provider/dialogue suites retain their earlier evidence.

The full metadata migration remains unfinished. Specialized metadata fields/types (including profiles, potion/book contents, custom attributes, trim, containers and persistent data), opaque `internal` NBT, older structured enchantment/mdata records and arbitrary third-party serializers remain unavailable. The original serialized ItemMeta map has no data-version field; the converter does not invent a source version for opaque data. Further work must establish the field's actual schema or obtain explicit version context before applying versioned fixes. Unsupported values remain recoverable rather than becoming degraded native items.

No production files were modified or deployed. Full Citizens/Interactions/Sentinel parity and actual client/modpack/proxy acceptance remain open; CmdCam owns camera playback and Yuuniverse Economy owns economic APIs.
