# Dialogue world-resolution parity

Updated: 2026-09-19 10:31:46 +08:00 (UTC+08:00). Implementation base: `64e7544`. Target: Minecraft 1.21.1 / NeoForge 21.1.248, JDK 21.

## Reference behavior

The installed Interactions 2.14.1 `ActionUtils.teleport` removes the exact `teleport: ` marker, splits the fields and calls `Bukkit.getWorld` with field zero. The marker contains one space; it does not trim the world name. Original `CraftServer.getWorld` lowercases using `Locale.ENGLISH` and looks up that exact registered name. Unknown names do not mean the overworld.

The original Arclight `DerivedWorldInfoMixin` derives names from the live root `ServerLevelData.getLevelName()`. With `symlink-world=false`, the standard names are the root, `<root>/DIM-1` and `<root>/DIM1`; custom dimensions use `<root>/<namespace>/<path>`. With symlinks enabled, Nether/End use `<root>_nether` and `<root>_the_end`, and custom worlds use `<root>_` plus `(namespace + "_" + path).replace('/', '_')`. Only the custom suffix is flattened. The stored NBT `LevelName` survives moving the save directory; the current folder basename is not authoritative.

The inspected old server has `level-name=uDays` and `symlink-world=false`. These are evidence of that server's names, not defaults embedded in the port. Reference hashes:

| Reference jar | SHA-256 |
|---|---|
| Interactions 2.14.1 | `ce4824a2bb4317eb9acd40cd4388eebedfab521d96310bf74f2d26f0ac85293e` |
| Arclight 1.20.1 snapshot 6859fcb | `e1cb06154d7bb0e6551c0666fa11416a54ba60efa879e8d7def36b93cd7d8458` |

Local disassembly is retained in `artifacts/legacy-action-utils.txt`, `legacy-world-resolution.txt` and `legacy-world-name-construction.txt`.

## Resolution and migration configuration

`Worlds.resolve` now returns only loaded dimensions. Native namespaced IDs retain their namespace; for example `missing:overworld` and the noncanonical `:overworld` cannot resolve to `minecraft:overworld`. Native requests are case-folded consistently with legacy world lookup.

For names without a colon, explicitly configured aliases take precedence. Otherwise the resolver collects matches from unique bare dimension paths and both legacy naming conventions derived from the live `WorldData.getLevelName()`. Several matches for the same dimension are harmless; matches for different dimensions are rejected. This covers bare-path collisions across namespaces and collisions caused by flattening custom paths. Exact native IDs and explicit aliases can disambiguate them.

There is no generic `world`, `world_nether`, `world_the_end`, suffix-only or unknown-name fallback. Such a name works only when it identifies a current dimension/root-derived name or has an explicit mapping. Matching does not trim whitespace or normalize backslashes. Teleport action parsing removes at most the one separator space, preserving significant spaces in the first field; other action parsers retain their current behavior. The port still accepts its existing case-insensitive verbs and optional separator space, which are syntax extensions rather than claims of exact legacy parser equivalence.

The new `config/interactions/world-aliases.yml` is a direct YAML map. It starts as a documented empty `{}` rather than inferring migration destinations. For example, an administrator who has established these destinations can write:

```yaml
FormerRealm: minecraft:overworld
FormerRealm/DIM1: minecraft:the_end
'Harbour annex': example:harbour
```

Keys are literal case-insensitive world names; targets are canonical lowercase namespaced dimension IDs. Namespaced keys are reserved for native identity and cannot be overridden. Targets may be absent when the file is read, but resolving an absent target fails without falling through to automatic names. Values are direct IDs, not alias chains.

Safe YAML parsing rejects invalid shapes, non-string keys/values, duplicate keys including case-folded duplicates, invalid IDs and empty names. A complete immutable map replaces the previous snapshot only after successful validation. Startup loads it, `/interactions reload` refreshes it, and shutdown clears static state. A malformed reload preserves both the previous snapshot and the invalid file, logs the error and reports it to the command sender while the remaining reload work continues.

## Actions and lifecycle

An unresolved teleport fails existing batch preflight before earlier item payment or later reward commands. Actual execution resolves the destination again. Resumed delayed tails recheck the current alias snapshot before payment: a changed target is used, and a removed mapping fails. The controller's reload command cancels old pending batches before loading the new configuration; the public alias loader itself only replaces resolution state.

These are native dimension/migration facilities, not a Bukkit world-manager implementation or a change to Citizens' separate location persister. Previously accepted unknown names intentionally stop working until the configuration identifies a real destination.

## Validation

The isolated connected-player fixture passes **89 world checks**, alongside **61 influence**, **84 scheduled-action**, **91 native-action** and **127 display** checks. It creates two actual datapack dimensions with the same path in different namespaces, performs real player transfers, verifies coordinates/rotation and recipient scope, dispatches the actual reload command, and checks malformed configurations, whitespace, stale mappings and real-tick resumed payment rejection. The world phase runs after other fixture controllers shut down so their lifecycle cleanup cannot erase its aliases. The Gradle runner requires `[WORLDAUDIT] COMPLETE` and all four earlier completion markers.

The dialogue suite passes **77 unit tests**, including eleven resolver tests for canonical IDs, automatic/flattened naming, ambiguity, explicit precedence, missing targets, locale independence, configuration errors and reload snapshots. No failures, errors or skips. The runtime fixture's stored `LevelName` equals its save folder name; the moved-save distinction is established by original/source inspection and the explicit-name resolver tests, not by a separate renamed-save runtime launch.

The final sequential general regression passes **107 NPC assertions** and **223 runtime probes**; normal `test build` passes **166 ordinary tests** with no failures, errors or skips. Release/source jars exclude runtime probes. Logs, completion markers and hashes are in `artifacts/worlds-validation-summary.json`. Interactions jar SHA-256: `17b90ac34e634b3abbb057e0ac515153e16bf2f51b4643b07b35caff026d2226`. Unrelated dedicated suites retain their previous evidence and were not rerun for this batch.

## Remaining scope

Action recognition remains **17/18**; server transfer is still missing. Remaining condition grammar, dialogue authoring/configuration and incoming-chat behavior, quest/saved-item fidelity, group capabilities, Citizens API/parameter behavior, Sentinel and physical-client/full-modpack acceptance keep the complete parity goal active. CmdCam owns camera playback and Yuuniverse Economy owns economic APIs. No production files were changed or deployed.
