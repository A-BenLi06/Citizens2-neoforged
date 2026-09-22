# Legacy firework item migration

Updated: 2026-09-22 20:03:00 +08:00 (UTC+08:00).

Citizens now migrates serialized Bukkit rocket/star metadata and the older structured `meta.firework` schema to native Minecraft 1.21.1 components. This finishes the persistence batch already in progress when the user selected the [Citizens and native ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md). Further priorities are Citizens core behavior and the retained providers' integration boundaries.

## Native behavior

`FIREWORK` becomes `minecraft:fireworks`; `FIREWORK_EFFECT` becomes `minecraft:firework_explosion`. Original nested effects use the alias `Firework` and original Bukkit Color wrappers. The resource table maps BALL, BALL_LARGE, STAR, CREEPER and BURST to their native shape names. Ordinal conversion would swap the last two shapes between APIs.

Power uses the original 0..127 range. Absent power is zero; an empty star has no invented explosion. Primary/fade RGB values, trail and flicker (native twinkle) survive native resave. Native codecs enforce the 256-explosion limit without truncation. Metadata for the wrong item, unknown shapes/fields, empty primary colors and invalid ranges preserve the complete unavailable definition, including pending edits.

The structured format reads numerically ordered effects and RGB maps, including sparse indices. Missing trail/flicker are false and absent fade colors are empty, matching the original Citizens loader. It normalizes into the same validated component converter. Encoded metadata keeps precedence over structured fields.

## Validation

Eight new unit tests verify original wrapper identities, all shapes/colors/flags, native resaves, absence/defaults, structured ordering, invalid-record retention and exact boundary values. The focused suite has 54 cases within 213 ordinary tests, with no failures, errors or skips.

The isolated metadata runtime fixture passes 280 checks, including 47 new firework checks. Native crafting accepts the migrated star and carries its exact explosion into rockets; fade crafting changes its fade color and leaves the source template intact. A native rocket retains the migrated stack through entity save/load, uses migrated power for lifetime and damages a nearby living entity on expiry. The fixture advances saved expiry time; it does not simulate a physical client's particles or Elytra flight.

Sequential regression gates pass 62 removal checks, 107 NPC assertions and 223 general probes on NeoForge 21.1.248. Normal test/build succeeds. Release/source jars contain the migration classes and shape resource, and exclude runtime probes and test fixtures. Detailed counts, original-writer provenance and source/fixture/reference/log/jar hashes are in `artifacts/fireworks-validation-summary.json`.

The nine stream fixtures have synthetic outer metadata maps serialized by the actual original Arclight/Bukkit writer with Guava 31. Valid nested firework/color objects are original Bukkit objects; malformed wrappers use an explicitly aliased fixture DTO. The three structured YAML records match the inspected original loader. No old server was started. The original Citizens save remains unchanged and contains six plain items with no metadata, so these fixtures do not establish production metadata acceptance.

Remaining unsupported material-data identities, special metadata types/fields, opaque NBT and arbitrary serializers remain unavailable without partial substitution. Actual client and target-modpack acceptance remain open. No production files or installed providers were changed.
