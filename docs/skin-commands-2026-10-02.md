# Native skin commands and asynchronous ownership

This follow-up completes the known `skin` command branches in the Minecraft 1.21.1 Citizens port. It belongs to Citizens; Interactions remains a separate mod/JAR with unchanged implementation.

## Commands and data

The command accepts name lookup, `-c` clear, `-l` latest-mode toggle with an optional name, `-u` texture URL output, `-e` PNG export, `--url` and `--file` imports, `-t <name> <data> <signature>`, `-s` skull items and `-b` Bedrock names. Branch precedence follows the original command: clear, URL output, export, import, direct texture, skull, then name/latest mode. During import, `-s` selects the slim model. Player cosmetic type and normal ownership/permission requirements apply.

Direct texture input validates the complete Base64/JSON structure before changing the trait. Empty `textures` objects remain valid default-skin data. File inputs require a PNG in the Citizens data folder's `skins` directory; normalized and real paths reject traversal or links outside that root. Images are bounded to 1 MiB and validated as 64x32 or 64x64 PNGs before upload. File takes precedence if URL and file are both supplied.

Exports read the existing texture's Mojang URL, reject foreign hosts/ports and redirects, retain the complete PNG rather than the original 10 KB prefix, and replace the destination atomically after a successful bounded download/validation. Errors preserve the old file. Skull items use a copied native `ResolvableProfile` component, enter the sender's inventory or drop at the command-source position when it is full. `-s` requires a spawned player-shaped NPC.

Clearing a skin removes stale cached identity and refreshes the native profile. Named lookup results populate the persisted texture cache; saved-mode NPCs retain that texture on later spawns. Latest mode re-enters the native profile lookup cache, which still controls Java service caching. Turning latest mode off during the first lookup retains that initial request. Java profile names and placeholder results use `Locale.ROOT` normalization.

Pending imports revalidate NPC registry identity, the exact skin trait and revision, sender connection and current permission/ownership before applying or reporting success. Direct choices, clearing, trait replacement, data reload and removal supersede old results. Named lookups carry the same trait revision; cache reset invalidates even requests whose cache entries were previously replaced. Entity/profile changes and completion feedback execute on the server thread. HTTP and image reads/decoding execute in background work; the final bounded export write/atomic move occurs after server-thread authorization.

## Services

Configuration is under `npc.skins`:

| Key | Default and purpose |
|---|---|
| `generation-endpoint` | `https://api.mineskin.org/v2/generate`, the supported synchronous v2 generation endpoint |
| `generation-api-key` | Empty; configure a MineSkin key when required by the service/account |
| `bedrock-api` | `https://api.geysermc.org/v2/`, for XUID and texture lookup |
| `bedrock-name-prefix` | `.`, used by `-b` and persisted Bedrock skin names |

The implementation follows the [published MineSkin v2 schema](https://github.com/MineSkin/api.mineskin.org/blob/main/openapi.v2.json). The schema recommends queue endpoints for clients managing queued jobs; this adapter uses its supported request/wait endpoint with a bounded timeout and reports failure for rate limiting or service errors. It does not silently claim success or retry paid requests. Existing Java method signatures and the legacy normalized result shape are preserved.

URL payloads use UTF-8 JSON without stripping escapes. PNG uploads use multipart data and the explicit classic/slim variant. Response status, JSON and texture fields are checked; error/debug paths do not print API keys or full service responses. Explicit Bedrock lookups encode gamertags correctly and construct UUIDs from the unsigned XUID, including short values and values above the signed-long range. No Floodgate Bukkit dependency is introduced.

## Validation and remaining acceptance

The [isolated native fixture](../tools/skin-command-runtime-audit/README.md) uses actual command dispatch, player admission, profile components and HTTP connections. A local service supplies generation/Geyser responses; a fixture-only proxy answers requests to the permitted Mojang export host. Java lookups use controlled futures in the native profile cache. These controls verify transport, errors and lifecycle behavior without uploading existing server skins or needing a paid API key.

Final native runs pass 127 checks each on NeoForge 21.1.248 and default 21.1.233. Related suites pass 367 skin-refresh/mirroring, 305 creation, 312 scoreboard, 216 mounted-transfer and 62 removal checks. General validation passes 107 NPC assertions and 223 probes. All 100 Interactions tests, 232 ordinary tests and clean test/build pass. Production checkpoints match dedicated validation; an EOF-only fixture cleanup is verified against the archived tested copy. Release/source archives exclude audit code; the separate Interactions archive has unchanged entry names and contents against the preceding release.

Source/service-schema checkpoints, logs, test reports and release/source archive comparisons are consolidated in `artifacts/skin-command-validation-summary.json`. Public MineSkin/Geyser availability, account authorization/rate limits, real signed-texture downloads, physical-client skin/model rendering and combined modpack behavior remain acceptance work. The [current checklist](remaining-citizens-work-2026-09-26.md) retains those requirements, debugging aliases and applicable data/provider/proxy work.
