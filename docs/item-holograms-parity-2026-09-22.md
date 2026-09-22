# Native Citizens item holograms

Updated: 2026-09-22 20:22:11 +08:00 (UTC+08:00).

Citizens now renders `<item:…>` hologram lines as native items. Previously the port logged a TODO and displayed the markup as text. This is Citizens-owned presentation work under the [current ecosystem guidance](neoforge-ecosystem-replacements-2026-09-22.md); retained dialogue, economy, permission and camera providers remain unchanged.

## Syntax and native rendering

Examples accepted by the line renderer:

```text
<item:DIAMOND SWORD:dark_red>
<item:minecraft:stone[minecraft:custom_name='"A > B"']>
<item:stone:custom_model_data=17>
```

The parser resolves native item IDs and components through the live registries. Complete native identities take precedence over the older material:modifier form, so a namespace is not treated as a color prefix. Legacy material case/spaces normalize to registry syntax. Colors use native team colors; as in the inspected reference, choosing a team color does not itself enable the glowing entity flag. Unknown identities, malformed components, formatting-only colors and trailing unparsed input do not produce a plain-item substitute. Original line text remains available for editing and persistence.

Quoted `>` characters belong to component strings. As in the current reference renderer, the first item token defines the line's item. Component values retain their case and content. This is native 1.21.1 component syntax; it does not interpret arbitrary old item NBT or Bukkit plugin serializers.

The default renderer creates a native item riding an invisible, gravity-free point armor stand. Both helper NPCs use the temporary registry, parent click redirection and tracking settings. Native item flags prevent pickup and age expiry. The anchor follows the parent's bounding-box height and the original 0.21 bottom / 0.07 top item margins. An explicitly supplied `ItemDisplayRenderer` uses a native item display mounted on the parent; its renderer choice now survives save/load. Public persistent `addLine(text, renderer)` also correctly persists its line.

Editing a line rebuilds the correct item or text renderer, removing previous entities. Unavailable items leave no substituted entity and preserve the raw line. Copies, despawn/respawn, temporary lines, trait removal and externally removed helpers use the ordinary NPC lifecycle. Changed view ranges reach both helpers. Destroying a click-redirect helper now unregisters it from the parent's PlayerFilter children, preventing old UUIDs accumulating after repeated rebuilds.

## Validation and boundaries

Four new parser unit tests cover material/color identity, native and legacy component forms, quoted delimiters, unknown values, strict full-input consumption and first-token behavior. The isolated runtime fixture passes 101 checks across real server ticks, including native entity flags/positions, helper membership, copied NPCs, file retention, mode changes and cleanup. Its initial compile used private display getters and was corrected to native serialized entity data; its first launch exposed a test's relative file path, corrected to the absolute fixture path.

Final sequential NeoForge 21.1.248 validation passes 217 ordinary unit tests with no failures/errors/skips, the existing 80-check text-editor fixture, 62 removal checks, 107 NPC assertions and 223 general probes. Normal test/build succeeds; release/source jars contain the new renderers/parser and exclude audit classes and fixtures. Citizens jar SHA-256: `b2a189ec3d9a8810d3ea314a4e227710cefe0de7f61c128af792355ad993d11d`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Detailed source/reference/test/log/jar hashes are in `artifacts/item-hologram-validation-summary.json`. The previous firework and provider validations retain their recorded evidence; this core presentation change does not claim to rerun every independent provider suite.

The implementation follows the checked-in Citizens reference renderers and separately inspected original Citizens 2.0.32 line bytecode. The older original has its item behavior inside HologramLine/outer methods rather than the later ItemRenderer class. Same-name class absence is not a missing-behavior claim.

Physical-client bobbing, interpolation, item model/glow appearance, actual mouse input, cross-dimension presentation, per-viewer packet holograms and full target-modpack acceptance remain open. No production data or installed provider was modified or deployed.
