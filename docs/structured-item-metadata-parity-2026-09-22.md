# Older Citizens structured item metadata

Implementation base: `005b59b`. Target: Minecraft 1.21.1 / NeoForge 21.1.248. This extends Citizens' item persistence; external item editors retain ownership of their authoring workflows.

## Source and conversion

The original Citizens 2.0.32 loader supports an older DataKey map format as well as `meta.encoded-meta`. Fresh disassembly of the original jar matches the retained reference exactly. Its root `enchantments` map uses enchantment names and integer levels. Structured `meta` uses different field names and plain-text setters from Bukkit's serialized ItemMeta maps, so it requires its own reader.

`LegacyStructuredItems` validates and normalizes the recognized fields, then uses the existing native component converters and complete item codec. It supports:

| Structured fields | Result |
|---|---|
| Root `enchantments` | Native enchantments using historical Bukkit names or canonical registry IDs. |
| `custommodel`, `repaircost`, `unbreakable`, `flags` | Native model/repair/unbreakability/tooltip components. |
| `displayname`, `lore` | Legacy section-sign text; JSON-looking strings, ampersands and markup remain literal. |
| `enchantmentstorage` | Stored enchantments on an enchanted book. |
| `armor.color` | Valid RGB dye on a native dyeable armor item. |
| `book.pages`, `book.title`, `book.author` | Writable text or signed-book components, according to the actual item. |
| `potion.data`, `potion.effects` | Original base-potion combinations and named custom effects. |

Indexed maps are read in numeric order with sparse indices preserved as ordering information. Lists are also accepted. Negative, nonnumeric and numerically duplicate indices reject the record. Numeric values must be exactly representable as integers; invalid booleans cannot silently become false. Unsupported top-level/nested metadata fields, incompatible subtype combinations, unknown enchantments and conflicting enchantment aliases retain the whole unavailable definition.

Names, lore and signed pages go through the original kind of plain-text setter. For example, a structured page containing `{"text":"literal page"}` remains those literal characters. Serialized ItemMeta pages retain their separate JSON-component behavior. Plain-text line breaks keep the earlier decoder's documented preservation behavior. Empty writable-book title/author values are harmless; nonempty latent fields remain unavailable. Native codecs enforce content limits without truncation.

Successful encoded metadata takes precedence over the old root enchantments and structured fields, matching the original `setItemMeta` replacement. Corrupt encoded metadata does not fall back to a partial structured item. The registered string reader still owns encoded metadata; it is not called with a fabricated encoding of a structured map. Existing deserialization hooks and explicit editable-component overrides run after successful conversion, and failed conversion leaves pending edits intact.

## Potion identities and semantics

`GeneratePotionMappings.java` runs the original Bukkit `PotionData` validation and `CraftPotionUtil.fromBukkit` conversion offline. It extracts 43 valid type/extended/upgraded combinations and 33 historical `PotionEffectType` names. The names also match the original `CraftPotionEffectType.getName` bytecode. These results are stored as resource tables, with the earlier numeric-effect table providing native identities. There is no dependency on current numeric registry positions and no Bukkit runtime dependency in the released mod.

Impossible extended/upgraded combinations remain unavailable. Original `UNCRAFTABLE`/`minecraft:empty` becomes an absent native base potion, as does missing base data. No water base is invented. Repeated custom effect types replace their earlier value in its original position, matching `addCustomEffect(..., true)`. The original four-argument effect constructor supplies visible particles/icons; ambient, duration and amplifier retain their stored values. Native range checks still reject degradation through clamping.

## Evidence and boundaries

`structured.yml` contains synthetic DataKey records matching the inspected loader, not production metadata samples. The original Citizens save has six plain item records and no encoded or structured metadata; it cannot establish real-save metadata acceptance. The unchanged original save hash and original jar/reference hashes are retained in the validation evidence.

Final sequential validation passes 46 focused item tests within 205 ordinary tests, with zero failures/errors/skips. Eight structured unit tests are new. The live metadata audit passes 233 checks: the prior 97 plus 136 structured checks covering eight available records/native resaves, native holders/dye, NPC inventory/copy, real potion effects, thirteen unavailable records, all 43 potion combinations and all 33 effect names. Regression gates pass 62 removal checks, 107 NPC assertions and 223 general probes. The initial test compilation used a nonexistent single-hook setter; it was corrected to the existing `setHooks` API before validation.

Normal build succeeds. Release/source jars include `LegacyStructuredItems` and the mapping resources and exclude audit classes and test fixtures. Citizens jar SHA-256: `127e167311e33d117118b83096a7428ed065de5d4fa738172050324c01aa18b2`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Detailed logs, source/fixture/reference/writer/unit-report/jar hashes and original-save integrity are in `artifacts/structured-items-validation-summary.json`. Dedicated provider/dialogue and standalone-stream audits retain their previous evidence and were not rerun.

The reader still rejects pre-flattening `mdata`, unrecognized material identities, structured fireworks/maps/block states/skulls/banners/crossbows/custom attributes, other unimplemented ItemMeta fields, opaque internal NBT and arbitrary serializers. Those schemas require their actual native equivalents before they can be loaded completely. No source data version is guessed.

No production files or installed providers were changed. Physical-client and complete modpack acceptance remain open. Existing Yuuniverse Interactions, Yuuniverse Economy and CmdCam integrations retain their responsibilities.
