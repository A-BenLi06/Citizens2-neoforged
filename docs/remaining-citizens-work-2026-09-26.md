# Remaining Citizens work

This is the current command and acceptance checklist for Minecraft 1.21.1. The September 13 audit contains historical gaps; later follow-ups supersede those rows. See the [ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md) for provider ownership.

All 37 historically missing 1.21.1 `/npc` command names have entry points. Entry-point coverage is not behavior parity, and the historical missing-command count must not be used as today's backlog or a completion percentage.

A read-only declaration scan of the checked-in `main` and `neoforge` source trees on 2026-09-27 10:55:42 +08:00 (UTC+08:00) leaves eight unmatched pairs: `npc chicken`, `npc cow`, `npc pig`, `npc coppergolem`, `npc mannequin`, `npc sulfurcube`, `waypoints hpa` and `wp hpa`. The first six concern newer-version entity capabilities; the last two are debugging aliases. Evidence: `artifacts/command-inventory-2026-09-27.json`. This scan does not validate parameter handling or runtime behavior.

## Commands still requiring implementation or comparison

| Command | Remaining difference in the current implementation |
|---|---|
| `skin` | URL/file input, supplied texture/signature, export and skull branches. Bedrock-specific integration depends on an applicable native provider. Name lookup, clear/latest mode, refresh and mirrored appearance already exist. |
| `armorstand` | Original body, head, left/right arm and leg pose parameters; ordinary switches already exist. |
| `create` | Item-provider input, timed temporary duration and original unspawned/center/packet shortcuts. Native item/packet backends exist; strict destination parsing was already repaired. |
| `list`, `select` | Registry/tag/expression filtering and original selection range/registry options. Basic list pagination, type/owner filters, ID/name and nearest selection exist. |
| `anchor`, `chunkload`, `collidable` | Cursor-target anchor saving, temporary chunk loading and fluid-push options. Existing anchors, chunk tickets and collision switches remain implemented. |
| `spawn`, `tp`, `tphere` / `move` | Original name/option support and exact/front/cursor/center behavior. The current `tphere` declares `-c` without inspecting it. Coordinate movement through `moveto` exists. |
| `powered`, `name`, `home` | `powered --set`, `name -h`, and original home flag/duration spellings. Toggling, hover-name alternatives and home behavior already exist. |
| `waypoints hpa`, `wp hpa` | Original debugging aliases require a usefulness/native-backend decision; ordinary waypoint navigation exists. |

Recent follow-ups cover `follow`, `mount`, `pose`, `lookclose` / `look`, native command locations, removal targeting, text editing, entity/display commands and particle options. Their unverified client/modpack scenarios remain acceptance work. They should not be listed again as wholly missing commands. `horse --color` and `--colour` both exist; upstream's unused `minecart --item` parameter is not a missing implemented behavior.

## Behavior, data and integration acceptance

- Mounted passenger-tree transfer now has 216 dedicated callback, failure, retry and player-session checks; see [the transfer follow-up](mounted-tree-transfer-2026-09-27.md). Physical seat layout and interpolation still need real clients; custom-controller/modpack transfer contracts remain separate acceptance.
- Unmounted hologram helpers need a separate cross-dimension follow-up. Source review shows `SingleEntityHologramRenderer.teleport` only changes coordinates in the helper's existing level, while `HologramTrait.run` notices a parent dimension change without recreating those live helpers. Mounted helpers belong to the transferred tree; unmounted text/cloud/item anchors do not. A targeted parent-transfer fixture and native lifecycle fix remain required; the existing viewer-dimension regression does not prove this path.
- Convert additional legacy item profiles, attributes, containers, persistent data and old material identities when required by actual content. Preserve unavailable data. Original item IDs 6, 15 and 144 have no source definitions; package 163 still needs its real `eyemod:eyephone` provider.
- Review the native extension contracts consumed by integrations. An absent Bukkit event class name alone is not a requirement for a wrapper. Inbound Paradigm text evaluation requires an appropriate provider API.
- Exercise skins/texture services, labels/holograms, look rotation, GUI prediction/anvil input, mounting, dialogue while riding and provider lifecycle together with physical clients and the target modpack.
- Verify actual `send_to_server` arrival through the proxy and both backends. Encoding and dispatch checks already pass; actual arrival has not been accepted.
- Resolve the required legacy group definitions, shop destinations and account identities through Paradigm and Yuuniverse Economy. Keep Yuuniverse Interactions and CMDCam as their respective owners. FTB Quests and RSItemEditor adapters/data migration are conditional on selecting those providers.

Full Quests, GroupManager, EssentialsX, PlaceholderAPI or Sentinel replication is not an implicit Citizens completion requirement. Future-version entity variants are outside ordinary Minecraft 1.21.1 parity. No production deployment or physical-client acceptance is implied by this checklist.
