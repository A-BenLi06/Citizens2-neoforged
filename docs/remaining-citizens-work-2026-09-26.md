# Remaining Citizens work

This is the current command and acceptance checklist for Minecraft 1.21.1. The September 13 audit contains historical gaps; later follow-ups supersede those rows. See the [ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md) for provider ownership.

All 37 historically missing 1.21.1 `/npc` command names have entry points. Entry-point coverage is not behavior parity, and the historical missing-command count must not be used as today's backlog or a completion percentage.

A read-only declaration scan of the checked-in `main` and `neoforge` source trees on 2026-09-27 10:55:42 +08:00 (UTC+08:00) leaves eight unmatched pairs: `npc chicken`, `npc cow`, `npc pig`, `npc coppergolem`, `npc mannequin`, `npc sulfurcube`, `waypoints hpa` and `wp hpa`. The first six concern newer-version entity capabilities; the last two are debugging aliases. Evidence: `artifacts/command-inventory-2026-09-27.json`. This scan does not validate parameter handling or runtime behavior.

## Commands still requiring implementation or comparison

| Command | Remaining difference in the current implementation |
|---|---|
| `skin` | URL/file input, supplied texture/signature, export and skull branches. Bedrock-specific integration depends on an applicable native provider. Name lookup, clear/latest mode, refresh and mirrored appearance already exist. |
| `create` | Item-provider input, timed temporary duration and original unspawned/center/packet shortcuts. Native item/packet backends exist; strict destination parsing was already repaired. |
| `list`, `select` | Registry/tag/expression filtering and original selection range/registry options. Basic list pagination, type/owner filters, ID/name and nearest selection exist. |
| `anchor`, `chunkload`, `collidable` | Cursor-target anchor saving, temporary chunk loading and fluid-push options. Existing anchors, chunk tickets and collision switches remain implemented. |
| `waypoints hpa`, `wp hpa` | Original debugging aliases require a usefulness/native-backend decision; ordinary waypoint navigation exists. |

Recent follow-ups cover [`powered`, `name` and `home` options](command-options-home-2026-09-28.md), [`armorstand` limb poses](armorstand-poses-2026-09-27.md), `follow`, `mount`, `pose`, `lookclose` / `look`, native command locations, removal targeting, text editing, entity/display commands and particle options. The command-options fixture passes 164 checks, including atomic validation, original duration/precedence, native powered admission and exact home navigation ownership. Four ordinary command groups remain above, plus the waypoint debugging aliases. Their unverified client/modpack scenarios remain acceptance work. They should not be listed again as wholly missing commands. `horse --color` and `--colour` both exist; upstream's unused `minecart --item` parameter is not a missing implemented behavior.

## Behavior, data and integration acceptance

- [Spawn and relocation commands](spawn-relocation-2026-10-02.md) now cover name/UUID/ID resolution, exact/front/cursor/center behavior, resolved-target ownership, cross-world permission checks, source identity and confirmed completion feedback. Native fixtures pass 302 checks on both 21.1.233 and 21.1.248. Legacy custom NPC implementations must implement the additive completion overload to report a confirmed teleport result; their existing void behavior remains available.

- Mounted passenger-tree transfer now has 216 dedicated callback, failure, retry and player-session checks; see [the transfer follow-up](mounted-tree-transfer-2026-09-27.md). Physical seat layout and interpolation still need real clients; custom-controller/modpack transfer contracts remain separate acceptance.
- The unmounted hologram dimension gap is addressed by [the parent-transfer follow-up](hologram-parent-transfer-2026-09-27.md), with 822 dedicated checks covering all built-in renderers, ordinary/virtual transport, native viewer packets, retry and callback ownership. Physical-client rendering and third-party renderer/modpack contracts still need acceptance; this is no longer an unimplemented built-in helper transfer path.
- Convert additional legacy item profiles, attributes, containers, persistent data and old material identities when required by actual content. Preserve unavailable data. Original item IDs 6, 15 and 144 have no source definitions; package 163 still needs its real `eyemod:eyephone` provider.
- Review the native extension contracts consumed by integrations. An absent Bukkit event class name alone is not a requirement for a wrapper. Inbound Paradigm text evaluation requires an appropriate provider API.
- Interactions `SWAP_HAND_CLICK` entry is implemented with 139 native packet/event checks; see [the swap-hand follow-up](swap-hand-dialogue-2026-09-30.md). Interactions continues to ship as its own NeoForge JAR and depends on Citizens in one direction.
- The ordinary `LivingEntity.die` and player-NPC death/respawn paths coordinate controller release, native cancellation/drops, virtual visuals and Citizens/Sentinel timers; see [the death lifecycle follow-up](npc-death-respawn-2026-10-02.md). The [armor-stand follow-up](armorstand-death-2026-10-02.md) handles native breaking/direct kills and complete equipment/stand drops. The [dragon follow-up](dragon-death-2026-10-02.md) now follows the native dying flight and 200-tick sequence, handles direct/phase-driven entry, advances dying virtual dragons, preserves private presentation and starts respawn delays after actual native completion. Both NeoForge versions pass the dedicated dragon fixture. Physical-client animation/audio, arbitrary mod overrides and combined modpack acceptance remain unverified.
- Exercise skins/texture services, labels/holograms, look rotation, GUI prediction/anvil input, mounting, dialogue while riding and provider lifecycle together with physical clients and the target modpack.
- Verify actual `send_to_server` arrival through the proxy and both backends. Encoding and dispatch checks already pass; actual arrival has not been accepted.
- Resolve the required legacy group definitions, shop destinations and account identities through Paradigm and Yuuniverse Economy. Keep Yuuniverse Interactions and CMDCam as their respective owners. FTB Quests and RSItemEditor adapters/data migration are conditional on selecting those providers.

Full Quests, GroupManager, EssentialsX, PlaceholderAPI or Sentinel replication is not an implicit Citizens completion requirement. Future-version entity variants are outside ordinary Minecraft 1.21.1 parity. No production deployment or physical-client acceptance is implied by this checklist.
