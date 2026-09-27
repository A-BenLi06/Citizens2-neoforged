# Armor stand pose commands and native persistence

The NeoForge `/npc armorstand` command now accepts `--headpose`, `--bodypose`, `--leftarmpose`, `--leftlegpose`, `--rightarmpose` and `--rightlegpose`, alongside its existing appearance switches. For example:

```text
/npc armorstand --headpose 15d,0d,0d --rightarmpose -90d,0d,0d --arms true
```

Each pose contains three angles separated by commas. A `d` suffix means degrees; a bare number or `r` suffix means radians, matching the original Citizens command. Quoted triples may contain surrounding whitespace. Vanilla `Rotations` performs its normal angle normalization. Saved `x`, `y` and `z` fields retain the legacy radian format.

All supplied options are validated before command mutation or armor-stand trait attachment. The command rejects missing/extra components, empty angles, non-numbers, nonfinite values, conversion/float overflow, unknown options, invalid booleans and surplus positional arguments. Its typed pose validator uses the existing localized invalid-value feedback. Selection, NPC type, ownership and permission checks remain in the native command framework.

The trait's six pose setters now update the currently owned native entity immediately and retain configuration while unspawned. Updating one limb preserves other limbs changed externally since the previous tick. The command captures current poses and applies its appearance switches immediately without replaying all previously saved poses. Saving or despawning captures external pose changes that occurred before the next tick. Retired trait pose setters and lifecycle methods cannot change a replacement trait's entity.

## Verification

The opt-in [native audit](../tools/armorstand-runtime-audit/README.md) runs on an isolated loopback server. The retained negative run, `artifacts/armorstand-negative248.log`, reproduces a command reporting success while leaving the head pose unchanged (`immediate_head`). Intermediate fixture corrections record Brigadier exception feedback and provide a real native embedded player connection for player-targeted messages; these were test-harness corrections, not additional production defects.

The final gate is `artifacts/armorstand-final248.log`. It covers each limb, combined commands, units/whitespace/normalization, native metadata, real entity ticks, atomic rejection, external edits, immediate trait setters, legacy radian persistence, unspawned configuration, respawn, retired trait references, virtual NPCs, native metadata packet encoding and player permission/ownership checks. The consolidated counts, sequential regressions, ordinary tests, archive checks and hashes are recorded in `artifacts/armorstand-validation-summary.json` and the root walkthrough.

Validation uses NeoForge 21.1.248; the default remains 21.1.233. Synthetic native sessions and metadata checks do not certify physical-client rendering or arbitrary modpack behavior. Original production saves are not modified, and actual proxy arrival remains separate acceptance.
