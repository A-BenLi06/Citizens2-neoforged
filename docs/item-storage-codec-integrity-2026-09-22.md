# Citizens item codec integrity

Implementation base: `0c86b38`. Target: Minecraft 1.21.1 / NeoForge 21.1.248.

## Behavior

`ItemStorage.saveItem` previously removed legacy fields before encoding a native replacement. If a component codec rejected that stack, the method logged an error and returned normally. An inventory save had already deleted the entire slot. Strict snapshots also treated the silent return as success, allowing NPC removal or copying with an incomplete item record.

The item codec now completes before any fields change. Codec errors propagate as `IllegalStateException` with the item path and codec diagnostic. The existing strict snapshot path can therefore stop a destructive command before it removes an NPC. Inventory saving retains the previous nonempty slot until its replacement is encoded. Explicit empty/null items still clear their saved fields, and a successful replacement removes obsolete legacy fields as before.

Native loading now accepts only a complete codec result. An unknown or invalid component cannot produce a usable partial stack. A failed read logs its path, returns null and does not consume pending editable-component overrides. This is a per-item codec guarantee, not a transaction around all NPC fields, storage I/O or addon hooks. Preservation/recovery of unavailable items across a later owner save remains separate work: existing owner APIs do not yet distinguish an unreadable item from an empty slot.

## Separate legacy format

Read-only inspection of the actual Citizens 2.0.32 binary confirms that it writes `meta.encoded-meta` through `BukkitObjectOutputStream`. The newer reference source also accepts a scalar `meta` value. Arclight's stream substitutes a `Wrapper` containing the ItemMeta serialization map, including its `==` alias, and Guava's immutable collection serialization. This format is distinct from ItemEdit's Base64/gzip NBT and must not be fed to that decoder. A dedicated legacy decoder and support for older structured metadata remain open; no Bukkit classes were added to the production classpath.

The inspected original Citizens save has SHA-256 `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`. Its six item records are ordinary `type_key`/`amount` entries without metadata. They do not establish acceptance for serialized ItemMeta. Reference bytecode and the read-only inventory are retained in `artifacts/legacy-citizens-item-storage.txt`, `artifacts/legacy-bukkit-object-stream.txt` and `artifacts/legacy-citizens-items-inventory.json`.

## Validation

Three new unit cases cover native codec failure retaining complete native/legacy records, unknown/invalid component rejection without source mutation, and successful replacement/explicit removal. The focused ItemStorage suite has ten tests. Seven added removal-fixture checks verify live-registry enchantment round trips, inventory save failures, actual removal/copy rejection, corrected retry, exact undo and explicit slot clearing. The complete removal fixture passes 51 checks.

Final sequential validation passes 51 removal checks, 107 NPC assertions, 223 general runtime probes and 169 ordinary tests, with zero unit failures/errors/skips. The normal build succeeds and release/source jars contain no runtime probes. Citizens jar SHA-256: `a1b998a46a106ed8d4a1f37ca78686574800688379715074bd5256eacb82e92c`. The Interactions jar is byte-identical to the preceding saved-item batch. Evidence and log/source/jar hashes are recorded in `artifacts/item-storage-validation-summary.json` and `walkthrough.md`. Unrelated dedicated suites were not rerun. Production server files were not modified or deployed. The full parity goal remains active.
