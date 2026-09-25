# Look-close target admission and owned lifecycle

Minecraft 1.21.1 / NeoForge 21.1.248. Scope follows [the ecosystem guide](neoforge-ecosystem-replacements-2026-09-22.md).

Reviewed: 2026-09-25 11:37:14 +08:00 (UTC+08:00).

The previous candidate scan admitted nearby spectators and invisible players before checking eligibility. A nearer invisible player could therefore become the actual target, and private rotation sessions could be created for players the NPC should ignore. Disabling or removing LookClose did not release those persistent sessions. The isolated negative control reproduces `nearest_invisible_player_is_not_selected` against commit `0bc5b9a`.

## Target and lifecycle contract

Physical and private modes now use the same eligibility checks: current admitted player identity, live entity/connection, dimension, finite nonnegative range, NPC exclusion, spectator/native invisibility/potion state, optional native line of sight and the programmatic entity filter. Eligibility is checked again after a custom filter runs. Invalid range updates fail before mutation. Existing targets and the pose-suppression query use that policy as well.

Target-change events keep valid redirection and null suppression. Invalid redirection retains the eligible proposed target; it cannot install an invisible/disconnected target. Selection is guarded against recursion and checks the current trait, entity, enabled mode and configuration revision after callbacks. Disable, mode change, trait replacement and despawn/respawn inside an event cannot commit an old selection.

Disabling, changing modes, removing the trait and despawning release the private sessions owned by this LookClose instance. Cleanup uses exact session identity, so it does not end a later session created for the same UUID by another caller. Current paired viewers with no remaining replacement session receive native body/pitch and head packets restoring the real entity's angles. This also reaches an invisible viewer, who still sees the NPC. Live head/body policy changes replace the old private parameters on the next update.

Physical target rotations carry a revision so cleanup cancels only the rotation last issued by LookClose. A later pose/rotation request survives. Cancellation stops a persistent physical session without rewriting its configured persistence policy, and a new target can activate it again.

Restoration follows `ServerEntity`'s native wire fields: entity yaw and pitch in the movement-rotation packet, with head yaw in its separate packet. The dedicated checks deliberately make body-animation yaw different from entity yaw so the two cannot be confused.

Navigation pauses use `Navigator.getPauseRevision()`, implemented by the native navigator. Every explicit pause request advances that revision, even if its Boolean value is unchanged. LookClose releases only its last request: preexisting pauses and a later repeated `setPaused(true)` survive, while a replacement route does not remain paused solely by a retired LookClose request. Private mode does not pause navigation.

The trait now consumes the reference enabled/random-look defaults and `npc.default.look-close.disable-while-navigating` (default true). This also fixes the first `/npc lookclose` command on an NPC without the trait: it now enables looking, and the next invocation disables it.

## Evidence and limits

The dedicated loopback fixture on port 25610 passes 63 checks. It admits actual ServerPlayers, exercises the real command dispatcher, native line-of-sight occlusion, target events and navigation, and captures native rotation stream encodings. Both world and virtual NPCs verify restoration to paired viewers; a final real-tick interval checks that disabled sessions stay released. The fixture also checks replacement owners, native invisibility, filter side effects, malformed ranges, command/default behavior and respawn/trait cleanup.

Sequential regression passes 81 movement/player-list checks, 108 tracking-admission checks, 43 packet-viewer checks, 107 NPC assertions and 223 general probes. All 232 ordinary tests pass with zero failures/errors/skips. The final native-yaw-field correction is followed by another successful dedicated run and normal test/build; other behavior is unchanged from the broader regression. Normal binary/source archives exclude opt-in audit and provider fixtures.

Citizens SHA-256: `7c70e8f71f069137f47a19c6d75e4b41e99537324229dc131a54220059ca48c0`; sources: `b24bfeb1c87190b833a4ba93f5991fd2546b7ab178a41581048f977d41fa00b0`. Interactions and the original Citizens save retain their prior hashes. Detailed source/reference/native archive/log/report hashes are in `artifacts/lookclose-validation-summary.json`.

```powershell
./tools/prepare-lookclose-audit.ps1
$env:JAVA_HOME = 'C:\Users\ben_l\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/lookclose-runtime-audit.gradle runServer --console=plain
```

The later [LookClose controls follow-up](lookclose-options-2026-09-25.md) implements the saved filter, original command options, range-key migration and NPC-targeting setting. This earlier batch validates the programmatic filter contract. The later [private-rotation follow-up](private-rotation-packets-2026-09-25.md) covers native packet projection, running fallbacks and late viewer admission. Physical-client interpolation/rendering remains separate acceptance work. The Bukkit plugin `vanished` metadata convention is not introduced. Complete client/modpack/proxy acceptance remains unverified, and no production deployment occurs.
