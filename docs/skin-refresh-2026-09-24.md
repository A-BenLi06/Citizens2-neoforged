# Native player NPC skin refresh and mirrored equipment

Validated: 2026-09-24 11:41:00 +08:00 (UTC+08:00), Minecraft 1.21.1 / NeoForge 21.1.248.

Skin refresh now recreates the receiving client's player entity as well as its profile entry. It uses the existing world or virtual tracker, retaining the server entity, inventory and mount relations. Mirrored equipment is applied to actual native pairing/update packets so a later ordinary equipment update cannot restore the NPC's own gear over the mirror.

## Client profile lifetime

Minecraft 1.21.1's `AbstractClientPlayer` caches `PlayerInfo`; that object owns a cached skin lookup. `ClientPacketListener.handlePlayerInfoRemove` removes the profile-map entry without replacing existing entities' cached references. A remove/add of tab-list information alone therefore leaves an already rendered player NPC using its previous skin. The native skin manager's cache key contains both UUID and packed texture property, so a fresh PlayerInfo can request a changed texture for the same NPC UUID.

`SkinPacketTracker.respawn` now asks the entity's owning tracker to unpair and re-pair current viewers. World tracking rechecks native range/chunk ownership and Citizens admission; virtual tracking rechecks its current trait/tracker, player instance, range, dimension and admission. Rejected viewers receive cleanup and retry through normal tracking. Synchronous recursive skin refresh for the same entity is suppressed with finally cleanup. Removed/replaced entities and retired virtual trackers cannot be revived by the remaining iteration.

The ordered packets are entity removal, profile removal, a fresh per-viewer profile, then the native entity pairing bundle. That bundle restores metadata, attributes, equipment and projected mounts. Native stop/admission/start callbacks still run. Unlike a server-side NPC respawn, this packet refresh does not recreate inventory, restart navigation or detach the actual server mounts.

Profile packets own copied GameProfiles and properties before asynchronous network encoding. Mirroring builds its recipient-specific profile without temporarily overriding the server entity. Minecraft entity UUIDs remain authoritative on the wire; Citizens persistent UUIDs can differ. Tab-list show/hide updates, including the delayed live-policy reassertion, now reach eligible virtual viewers as well as world viewers. Removed entities and recipients no longer tracking the NPC receive no new profile/list entry.

## Cleanup before callbacks

The new replacement fixture exposed an existing stop-event ordering hazard: an external listener could replace a virtual tracker and pair a fresh client entity during the old stop event, after which Citizens' old event handler removed the fresh profile. Profile, hologram-cache and mount-cache cleanup now happen in native `removePairing` before external stop-tracking callbacks. Nested replacement keeps its new state; old cleanup cannot erase it afterward.

## Mirrored equipment

The earlier periodic mirror cache did not observe ordinary equipment packets overwriting the client's display. `EquipmentPackets` now replaces the target NPC's equipment inside native pairing bundles and subsequent world/virtual broadcasts, using the trait's actual per-viewer equipment function and copied item stacks. Equipment-free pairing receives the mirror's initial slots after entity creation. Null function results become empty slots. Input packets, the server NPC's equipment and viewers' inventories remain unchanged; unrelated entity packets retain their original content.

Mirrored player names still use native profile-name semantics. A scoreboard matches player entries by profile name, so mirroring another player's name also uses that name for client team lookup. The NPC retains its own Minecraft UUID; no real player's profile identity or server scoreboard membership is reassigned. Physical-client mirrored-name/team presentation remains an acceptance item.

## Validation and scope

The [isolated audit](../tools/skin-refresh-runtime-audit/README.md) passes 319 checks. Synthetic players use real PlayerList admission, protocol encoding, chunk acknowledgements and native world/virtual tracking. Profile packets are codec-round-tripped into an independent profile map; entity replicas retain their creation-time profile and cached-info references according to the inspected native source. A negative control sends the former profile-only sequence and proves the fixture keeps the old rendered texture until the actual entity refresh occurs.

Coverage includes initial and changed skins, mirrored names/textures/equipment, queued profile immutability, custom equipment functions and null slots, unchanged source packets/inventories, native pairing state and ordering, world/virtual transport identity, mount chains and a real player riding an NPC, filters and same-tick range exit, reentry, reconnect, native player respawn, denied admission, recursive callbacks, listener destruction, virtual-tracker replacement and delayed-update cleanup. The stronger scoreboard fixture also checks the actual Minecraft profile UUID and disables unrelated external skin fetching.

The first scoreboard regression failed its single-spawn assertion. That fixture allowed asynchronous default skin fetching, which can now legitimately recreate a client entity; the original trace did not capture enough counts to establish that as the cause. The corrected fixture isolates scoreboard behavior by disabling that fetch, reports actual recipient spawn counts on failure, and passes all 312 checks. The failed run also exceeded the existing shutdown timeout while native chunk unloading executed `CompletableFuture.postComplete`; the captured thread trace does not identify a network wait. Both original diagnostics are retained alongside the passing retry.

Sequential regression passes 312 scoreboard, 108 tracking-admission, 170 mount, 179 metadata, 81 movement/list, 47 world-visibility and 62 removal checks, 107 NPC assertions and 223 general probes. The normal `test build` passes 217 ordinary tests with zero failures/errors/skips. Every Gradle process exited before the next began; the final batch exited 0. Release/source archives include the changed production classes and exclude audit/provider fixture classes. Citizens SHA-256: `e2f5cf9ad6a4a454088989348a0d532a5e09dd08bf1925a23645f526b91e273c`; sources: `66753f1a084f01560f6e15ad305e07b10f1e570a47d5e9db3bf6aad7b35fc6f7`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`.

The fixture's cache model is source-derived; it does not execute the client renderer or download/validate signed skin textures. Mojang service behavior, physical skin/model drawing, arbitrary modded clients, mirrored-name/team presentation and full modpack/proxy acceptance remain unverified. Detailed regression/build results and source/native/fixture/log/report/jar hashes are recorded in `artifacts/skin-refresh-validation-summary.json`, generated by `artifacts/summarize-skin-refresh.ps1`; native excerpts are in `artifacts/skin-refresh-native-source.txt`.

This work follows the [user-selected ecosystem boundary](neoforge-ecosystem-replacements-2026-09-22.md). Retained providers, the project default NeoForge version and production deployment remain unchanged. The broader Citizens goal stays active.
