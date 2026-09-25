# Native MiniMessage components

Minecraft 1.21.1 / NeoForge 21.1.248. Scope follows [the ecosystem guide](neoforge-ecosystem-replacements-2026-09-22.md).

Reviewed: 2026-09-25 11:06:35 +08:00 (UTC+08:00).

Citizens now uses MiniMessage 4.26.1, matching the checked CitizensAPI reference, and converts its parsed tree directly to Minecraft components. The parser and its runtime dependencies are embedded in the Citizens jar. No Bukkit platform adapter is included. This fixes the reproduced nested-tooltip failure: `<hover:show_text:'<red>hello > world</red>'>label</hover>` previously displayed `hello > world</red>'>label` in the message body.

## Native component contract

MiniMessage owns tokenization, quoting/escaping, tag nesting, decoration negation, resets, gradients and Unicode handling. The Citizens `csr` resolver clears decorations while retaining inherited colour, events and font, as in the reference implementation. Direct `TextParser` callers retain MiniMessage aliases such as `<b>`; section-sign codes are normalized separately. `Messaging` retains its own legacy aliases and configured colour-reset policy.

The native bridge preserves text, translation keys/fallbacks/arguments, keybinds, insertion, selectors, scores, block/entity/storage NBT, and supported click/hover events. Dynamic components remain structured when parsed without a source. Source-aware parsing and message delivery resolve them through Minecraft's `ComponentUtils` with the actual source entity and permissions. A failed resolution remains visible as the original input instead of elevating permissions.

Item hover payloads use native item/component codecs and the running registry provider. Modern component SNBT is decoded directly. The legacy whole-item-tag form is migrated from the final pre-component release schema, Minecraft 1.20.4 (data version 3700), through Minecraft's item data fixer; remaining custom data is retained. Entity hover types resolve against the real entity registry and retain UUID/name components. Unknown registries, malformed payloads, unsupported component types or newer protocol styles remain visible as original input. Native click serialization policy also applies, including the dedicated-server restriction on `open_file`.

Legacy projections now retain exact RGB values with section-sign hex sequences, matching the reference Bukkit serializer's modern-server configuration. They also reset colours when leaving a span and reapply decorations after colour codes. Events and other non-text information cannot be represented in that projection; callers needing them should retain the native Component.

Two stray closing quotes in the bundled Chinese text-editor prompt swallowed later buttons under proper MiniMessage parsing. The range and delay tooltip entries are corrected. Existing translation overrides remain authoritative and are not rewritten; copied older overrides with those typos require updating the affected entries.

## Evidence and reproduction

The focused negative control in `artifacts/text-parser-negative248.log` fails on the committed hand-written scanner. Parser unit tests cover quoted/nested tooltips, actual bundled editor text, styles/events, translation/keybind structure, Unicode gradients, unknown input and RGB projection.

The isolated fixture on loopback port 25607 passes 39 checks. It admits a real player and uses native chat-packet codec copies to check item/entity/text hovers, modern components and legacy NBT, unavailable data, permission-sensitive selectors, live scoreboard/storage values, English/Chinese editor bodies/tooltips/click values, actual NPC names and component persistence. JSON comparisons are structural, since native map serialization does not promise field order. Block NBT positions follow the reference MiniMessage grammar, including explicit relative offsets such as `~0 ~0 ~0`.

```powershell
./tools/prepare-text-parser-audit.ps1
$env:JAVA_HOME = 'C:\Users\ben_l\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/text-parser-runtime-audit.gradle runServer --console=plain
```

The release smoke scripts use only the built Citizens and Interactions jars. An existing NeoForge 21.1.248 library installation is read-only input; all game files, mods, configuration and saves belong to `artifacts/text-parser-release-server` on loopback port 25608. `run-text-parser-release.ps1` waits for startup, creates a gradient-named cow through the real command dispatcher, lists NPCs and stops its own server. Pass `-LibraryDirectory` and `-JavaHome` explicitly. This checks embedded dependencies without a development classpath.

Sequential regression passes 80 text-editor checks, 113 anvil-input checks, 107 NPC assertions and 223 general probes. All 226 ordinary tests pass with zero failures/errors/skips, and the normal build succeeds. The five Kyori runtime libraries are embedded at their intended versions alongside the existing three libraries; normal binary/source archives exclude audit and provider fixtures. Interactions and the original Citizens save retain their prior hashes.

The ordinary bootstrap smoke passes startup, gradient NPC creation, listing the spawned cow and clean shutdown. Its copied Citizens and Interactions jars match the normal build hashes. Citizens SHA-256: `a47777cc81a06b573b4606bb4b56ca8e067d6c478586769dfa9da0817c201a24`; sources: `933d026c160746075467dc7971a83da6223ebd6e45d5ff77f2f763ded7b7b350`.

Release-script validation also caught an empty-log rotation race in the fixture, now handled by treating empty content as a string. The initial command exposed a separate Citizens coordinate-parser defect: `--at 0,-60,0,minecraft:overworld` split the dimension namespace as another coordinate and failed after registration. Its reproduction is retained in `artifacts/text-parser-release-location-gap.log`. The MiniMessage commit's successful smoke used current-dimension coordinates; the later [native command-location follow-up](native-command-locations-2026-09-25.md) repairs dimension arguments and validates locations before create side effects, and restores the full dimension argument to the release smoke. The earlier smoke's logs are archived under `artifacts/text-parser-packaged-*`.

Detailed source/library/archive hashes and final regression results belong to `artifacts/text-parser-validation-summary.json`. Physical-client tooltip drawing, key bindings, language selection and combined modpack/proxy acceptance remain unverified. Protocol features introduced after 1.21.1 and external placeholder resolvers are not provided by this bridge. No retained provider, production data or default NeoForge version is changed, and no deployment occurs.
