# LookClose controls, filters and saved configuration

Minecraft 1.21.1 / NeoForge 21.1.248. Scope follows [the ecosystem guide](neoforge-ecosystem-replacements-2026-09-22.md).

Reviewed: 2026-09-25 19:56:25 +08:00 (UTC+08:00).

## Commands and validation

`/npc lookclose` and `/npc look` now apply all supplied settings together. The bare command retains enable/disable toggling. `-r` controls realistic line-of-sight checking, as in the original Citizens command. Scripts written for the earlier native implementation that used `-r` for random looking should use `--randomlook true` or `--randomlook false` instead.

Supported value options are `--range`, `--filter`, `--randomlook` (alias `--rlook`), `--randomlookdelay`, `--randompitchrange`, `--randomyawrange`, `--randomswitchtargets`, `--headonly`, `--linkedbody`, `--disablewhennavigating`, `--perplayer` and `--targetnpcs`. The existing native `-h`, `-p` and `-d` toggles remain available; an explicit value takes precedence over its short toggle. These operations use the existing translated messages, including the NPC name in range feedback.

All values are validated before attaching a trait or applying any settings. Booleans must be true/false; look distance must be finite and nonnegative; random ranges must contain exactly two finite, ordered numbers. Durations accept the existing duration grammar, including explicit ticks and seconds; bare values are ticks. Delay is clamped to at least one tick, matching the original command, and overflow is rejected. Malformed filters and unknown flags are reported through the command layer. Invalid combined commands leave the entire previous configuration unchanged.

## Filters and service ownership

Space-separated filter clauses are combined with AND. For example:

```text
type=minecraft:player permission=server.npc.view,server.story.open group=Citizens,Builders
```

`type` accepts a comma-separated set of native registry IDs or legacy enum-style names such as `PLAYER`. At least one type must match. `permission`/`perm` requires every named permission; `group` accepts any listed group. Permission and group values are passed individually to the existing PermissionUtil service boundary, preserving group spelling. Missing or unknown group service results do not grant access. No independent permission/group state or provider installation is introduced.

The original SpigotUtil helper splits permission/group values from the whole expression rather than the clause operand. The native parser uses the actual operand. Unknown clauses, unavailable entity types, empty list members and malformed assignments are rejected instead of silently broadening the filter. `none`, blank text or a null programmatic value clears the configured filter. A separately supplied programmatic predicate remains an additional restriction.

Loading a saved filter compiles it before it can admit targets. An invalid saved filter reports a load failure, retains its original text and rejects targets until corrected/reloaded. Physical and per-viewer selection both consume configured filters and retain the previous eligibility/callback checks.

## Saved ranges, random looking and NPC targets

The original `randomPitchRange` and `randomYawRange` keys and defaults are restored: pitch `[0,0]`, yaw `[0,360]`. Earlier native `randomlookpitchrange` and `randomlookyawrange` keys are read when the corresponding original key is absent. Saving emits the canonical original keys and removes the aliases. Canonical values win when both forms exist. Returned range arrays are copies. Invalid saved random ranges/delays report load errors and remain stored; the random tick path does not execute malformed ranges.

Random looking remains independently enabled from nearby-player targeting. It starts according to the original initial timer behavior, obeys the configured delay and fixed/ranged angles, and releases only its own pending physical rotation when disabled or retired. Later external rotation requests survive that cleanup. Random target switching chooses another eligible player at the configured interval.

`targetnpcs` is restored as an opt-in setting for **player-type Citizens NPCs**, including the owned entity state of virtual NPCs. Other entity types do not become LookClose targets. The target must still be alive, current, registered, in range/dimension and visible under the configured policy; an NPC cannot target itself. Turning the setting off or despawning the target releases that selection. Per-viewer mode only opens sessions for real players and does not scan NPC registries for recipient connections.

## Validation and limits

The isolated loopback fixture uses port 25612 and admits actual ServerPlayers. Its 88 checks exercise the real command dispatcher, atomic rejected inputs, original/native save formats and round trips, exact permission service operands, dynamic group decisions, unavailable filters, random angles and timing, ownership cleanup, and world/virtual player-NPC targets. A pre-change negative control at `1c5ae0d` reproduces `AssertionError: r_flag_selects_realistic_looking` in `artifacts/look-options-negative248.log`.

```powershell
./tools/prepare-look-options-audit.ps1
$env:JAVA_HOME = 'C:\Users\ben_l\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
./neoforge/gradlew.bat -p neoforge '-Pneo_version=21.1.248' -I ../tools/look-options-runtime-audit.gradle runServer --console=plain
```

Sequential regression passes the existing 63 LookClose lifecycle checks and 64 private-rotation checks, plus 107 NPC assertions and 223 general runtime probes. All 232 ordinary tests pass with zero failures/errors/skips. The normal build succeeds, and binary/source archive checks confirm the new parser is present and opt-in audit/provider fixtures are absent. Evidence, source/reference/native archive/log/report hashes and output jar hashes are in `artifacts/look-options-validation-summary.json`. Interactions retains its preceding rebuilt jar hash and the original Citizens save retains its recorded hash.

This validates the service boundary with controlled permission/group resolvers; it does not claim new acceptance of the deployed Paradigm build. Physical-client rendering and the combined modpack/proxy remain unverified. The original Shulker peek response is covered by the [subsequent native response and ownership work](shulker-lookclose-2026-09-25.md). No production deployment, provider installation or original-save modification occurred; the repository's default NeoForge version stays 21.1.233.
