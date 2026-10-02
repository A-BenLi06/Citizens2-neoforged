# NPC anchors, chunk loading and fluid pushing

This follow-up restores the remaining `anchor`, `chunkload` / `cload`, and `collidable` / `pushable` options in the Citizens NeoForge port for Minecraft 1.21.1.

## Behavior

- `anchor --save <name> -c` saves the native outline-ray block target; explicit `--location` or `--entitylocation` takes precedence. Sender and cursor locations have separate caches. Rays use current entity rotation so a just-received player look packet is not delayed by head interpolation. A missed ray retains the existing sender-location fallback.
- Anchor actions retain save, assume, remove precedence and subsequent `-a` behavior. Names/locations are validated before attaching a trait, case-insensitive duplicates are rejected, and location values are copied. Listing includes coordinates with translated labels. The legacy `name;world;x;y;z` format remains intact; unknown worlds and nonfinite persisted coordinates remain unresolved instead of being redirected to the overworld.
- `chunkload -t` changes only the session setting. The metadata store retains the previous persistent value when writing fresh snapshots, cloning or saving, and removes the override on reload. Permanent writes replace that backing normally. The additive `MetadataStore.setTemporary` default delegates to legacy `set`; custom stores must implement backing preservation to provide the same snapshot semantics.
- Chunk tickets update immediately on toggles and follow the current entity across chunks/dimensions. Metadata-only enabled NPCs attach the ticket trait during spawn. Exact trait/entity/registry ownership prevents retired traits from acquiring tickets. Separate NPC registries sharing a native UUID/chunk ticket key use reference-counted holders, and independent vanilla force-loading is preserved. Global shutdown cleanup has its own event-subscriber class because traits register as instances.
- `collidable --fluids true|false` persists fluid policy without changing ordinary collisions. The native flow-accumulation gate honors both the entity/fluid's own veto and NPC policy, preserves fluid-height/contact calculations and prior motion, and also applies to retained death visuals. The implementation uses `FluidType` for native and modded fluids, without water/lava-specific production branches.

## Module ownership

These are Citizens capabilities. Interactions dialogue, conversation state and input/session behavior remain under `neoforge/src/dialogue` with mod ID `interactions`. The build emits `yuuniverse-interactions-2.0.43-neoforge-SNAPSHOT.jar` separately from Citizens. Interactions depends on Citizens; Citizens has no Interactions dependency. The Citizens archive does not bundle Interactions classes, resources or mixin configuration.

## Validation

The [isolated native fixture](../tools/world-options-runtime-audit/README.md) covers the commands, metadata persistence, native tickets, water/lava flow, virtual NPCs, retained death visuals, vanilla behavior and native turtle vetoes. See `artifacts/world-options-validation-summary.json` for completed gates, tested source hashes and archive comparisons.

Final dedicated runs pass 213 checks each on NeoForge 21.1.248 and default 21.1.233. Related regressions pass 302 relocation, 49 command-location, 216 mounted-transfer, 312 scoreboard and 1,639 death checks. General validation passes 107 NPC assertions and 223 probes. All 100 Interactions tests, 232 ordinary tests and clean test/build pass. Release/source archives exclude audit fixtures, and the separate Interactions archive has unchanged entry names and contents against the preceding release; ZIP timestamps can change the overall archive hash.

Physical-client presentation and combined modpack behavior, including arbitrary custom fluids/entity overrides, remain unverified. The remaining `skin` branches, waypoint debugging aliases and applicable data/provider/proxy work remain on the [current checklist](remaining-citizens-work-2026-09-26.md).
