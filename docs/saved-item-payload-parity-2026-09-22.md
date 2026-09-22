# Saved item internal payload migration

Implementation base: `7d5e8fd`. Reference: ItemEdit's original 140-entry database and Arclight 1.20.1, data version 3465. Target: Minecraft 1.21.1 / NeoForge 21.1.248 with the installed item providers.

## Reference findings

The original database SHA-256 is `e43f10cce831504cdbb033475b3379f7af2b48c6a1c2351177a8f5e915aaf94e`. All 140 entries carry version 3465. Metadata contains 127 lore definitions, 17 display names, six written books, and thirteen `internal` payloads. The independent inspector records each NBT type/value and compressed-payload hash in `artifacts/legacy-saved-item-payloads.json`; it never modifies the source database.

`CraftMetaItem` decodes `internal` as Base64 containing gzip-compressed Minecraft compound NBT. It restores unhandled tags onto the item. This is not an unreadable Java-serialized Bukkit object. The port previously discarded it. Reference disassembly is retained in `artifacts/legacy-craft-meta-item.txt`.

| Original items | Original data | Current native representation |
|---|---|---|
| Music discs 1001–1009 | `NetMusicSongInfo` with URL, name and seconds | `netmusic:song_info`, with `time_second` |
| Fuel can 156 | `jerrycanFluid` | `minecraft:custom_data`, read by the vehicle wrapper |
| Packages 161–163 | `Sender`, `Message`, and slotted legacy `Items` | `refurbished_furniture:package_info` plus `minecraft:container` |

The vehicle item also exposed a material resolver gap. Arclight's `ResourceLocationUtil.standardize` strips punctuation after replacing the namespace separator: `mts:mts.jerrycan` becomes `MTS_MTSJERRYCAN`. Registry-based matching now follows that rule and rejects ambiguous matches; no special fuel-can alias was added. Namespace underscores, existing material spelling extensions and explicit migration aliases remain supported. Native IDs retain their namespace meaning. Item matching resolves a material once per query instead of scanning the registry for every inventory slot.

## Implementation

`LegacyItemData` reads compressed NBT with the native reader, a 16 MiB allocation quota, and trailing-data rejection. Internal data requires an explicit valid integer source version. Mojang's item-stack data fixer upgrades standard tags to modern components. Unknown provider NBT remains custom data.

The two providers that changed their storage model receive explicit migrations backed by their registered component codecs; no optional mod classes are linked into production code. Music fields remain exact. Package children recursively undergo the same versioned conversion, preserving slot numbers, counts, standard components and provider data. Existing aliases apply to original nested identities once; an already resolved root item is not aliased again. Missing children, duplicate slots, wrong types and invalid components reject the entire saved item instead of creating a partial or empty reward.

The loader builds a replacement snapshot, rejects duplicate YAML keys, and preserves the previous snapshot after an unreadable/malformed whole file. A successfully parsed file can remove definitions; individual unavailable definitions are reported and omitted from that new snapshot. Source definitions are never rewritten, so a future provider installation and reload can resolve them. Callers receive independent stack copies.

All nine music discs, the fuel can, and packages 161/162 resolve through the actual provider jars. Package 161 also requires the existing banknote alias for its nested original currency item. Package 163 remains unavailable: the installed target has no `eyemod:eyephone`. Its failure is now visible before dialogue payment. Missing original IDs 6, 15 and 144 are still absent from the source and have not been fabricated.

## Validation and boundaries

The dedicated real-provider fixture passes 72 checks: native provider accessors, exact original payload values, nested aliases/lore/counts, sparse and nested containers, invalid structure rejection, preserved unknown data, actual packet and save/load codecs, reward/payment behavior, real package opening and unchanged templates/source. Its reference subset loads 139 of 140 definitions, with only the unavailable EyeMod package omitted. The six new unit cases cover compressed NBT failures/types, standard version migration, source versions, material punctuation/collisions, copy independence and reload behavior; the dialogue suite has 99 tests.

Final sequential NeoForge 21.1.248 validation passes all seven dialogue phases (39 condition, 51 transfer, 89 world, 61 influence, 84 scheduling, 91 native-action and 127 display checks), 107 NPC assertions, 223 general runtime probes and 166 ordinary tests. Both unit suites have zero failures/errors/skips. The normal build passes; release/source jars exclude runtime probes and the Interactions jar contains the converter. Its SHA-256 is `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Log/source/provider/jar hashes and source database integrity are recorded in `artifacts/saved-items-validation-summary.json` and `walkthrough.md`.

Actual playback of remote music, refueling a vehicle, client rendering and complete modpack acceptance remain unverified. Other metadata forms outside the inspected ItemEdit data and Citizens' separate serialized ItemMeta storage remain wider parity work. Production server files were not changed or deployed.
