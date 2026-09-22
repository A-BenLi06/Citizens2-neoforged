# Bukkit armor, book and potion metadata

Implementation base: `1f7c9c8`. Target: Minecraft 1.21.1 / NeoForge 21.1.248. This extends the [ItemMeta stream decoder](bukkit-item-metadata-parity-2026-09-22.md).

## Recognized schemas and native behavior

`LegacyBukkitMetaTypes` validates each subtype's fields before applying components. Unknown fields, unsupported nested aliases, invalid values and incomplete native codecs reject the whole definition; the existing item owners retain its original data for recovery. Common metadata still applies to supported subtypes, and tooltip flags apply after subtype components exist.

| Bukkit type | Native conversion |
|---|---|
| `ARMOR` | Armor trim material/pattern from canonical keys and live native registry holders. |
| `LEATHER_ARMOR` | Dye color, including leather horse armor. |
| `COLORABLE_ARMOR` | Dye color and trim together. |
| `BOOK` | Literal writable pages, including JSON-looking strings, line breaks and ampersands. |
| `BOOK_SIGNED` | Page components, title, author, generation and resolved state. |
| `KNOWLEDGE_BOOK` | Canonical recipe names, including names whose datapack is currently unavailable. |
| `POTION` | Optional base potion, custom color and custom effects on drinking/splash/lingering potions and tipped arrows. |

Armor applicability uses native armor classes and live dyeable/trimmable item tags. Nested Bukkit `Color` wrappers require valid RGB channels; optional alpha is validated but does not affect native components because the original armor/potion implementation writes `Color.asRGB()`. The original dye and trim tooltip flags retain their effect.

Writable pages remain text. Signed pages use the existing JSON/legacy section-sign component converter and retain click events; native resolution is exercised separately. Native codecs enforce book limits without truncation. Writable-book records with latent author/title/generation/resolved fields remain unavailable because this path cannot preserve those fields in writable-book components. Missing recipe names remain stored identifiers, matching native knowledge-book storage; this does not claim successful consumption of a book whose recipe is absent.

Potion effect IDs come from the original Bukkit numeric identities in `legacy-potion-effect-ids.properties`, covering vanilla IDs 1–33. They are never interpreted as current native registry positions. Unknown/modded numeric identities remain unavailable. Duration `-1` retains infinite effects; values below `-1` and amplifiers outside `0..255` are rejected rather than clamped. Defaults match the original map constructor: ambient false, particles true, and icon visibility defaults to particle visibility. An absent base potion stays absent instead of becoming water.

## Fixture provenance

The expanded generator uses the original Arclight `BukkitObjectOutputStream`, its `Wrapper` and Guava 31.1-jre. Outer ItemMeta maps are synthetic maps matching the inspected serializers, not instantiated CraftMetaItem objects. Nested color/effect samples use actual original Bukkit `Color` and `PotionEffect` objects; default/malformed effect maps use a documented `PotionEffect`-aliased fixture DTO. The earlier Guava 25 fixture remains unchanged.

Thirteen new retained samples cover colors, trim, combined armor, both book kinds, recipes, potion contents/defaults, unknown effects, invalid effect levels, unavailable trim, oversized pages and unsupported latent book fields. Source bytecode, writer hashes and generated artifacts are retained under ignored `artifacts/`. The original Citizens save has six plain item records and no serialized metadata; it remains unchanged at SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

## Validation

Final sequential validation on NeoForge 21.1.248 passes 38 focused item cases within 197 ordinary tests, with zero failures/errors/skips. Six subtype unit cases are new. The expanded metadata fixture passes 97 checks, including eleven subtype/item combinations, exact native components and resaves, actual NPC armor/copies, non-player potion effects, player consumption with a returned glass bottle, arrow color, signed-book resolution and unavailable-record retention. Regression gates pass 62 removal checks, 107 NPC assertions and 223 general runtime probes.

The audit originally assumed a non-player consumes a potion stack and passed an empty firing weapon to the arrow constructor. Those fixture assumptions were corrected to match inspected native code; production conversion was unchanged. Separate non-player and NeoForge FakePlayer cases verify the native potion branches, and the arrow is constructed with a bow.

Normal build succeeds. Release/source jars contain the subtype decoder and both alias resources and exclude runtime probes and Base64 fixtures. Citizens jar SHA-256: `d214e84a94b696d982a0fdab4dd505c7aa524cd1e4bf7d6922f4edc4a4802814`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Detailed log, source, fixture, writer, bytecode, unit-report and jar hashes are in `artifacts/bukkit-types-validation-summary.json`. Earlier standalone-stream/provider/dialogue suites retain their historical evidence and were not rerun for this batch.

## Remaining work

Profiles/skulls, custom attributes, other specialized types/containers/persistent data, opaque internal NBT, older structured `enchantments`/`mdata` and arbitrary third-party serializers remain unfinished. Serialized ItemMeta has no source data-version field; this implementation does not guess a version for opaque NBT. Source schemas that cannot be represented completely remain unavailable.

Physical-client, complete modpack and proxy acceptance remain open. No production files were modified or deployed. The full Citizens/Interactions/Sentinel parity goal remains active; CmdCam owns cameras and Yuuniverse Economy owns economic APIs.
