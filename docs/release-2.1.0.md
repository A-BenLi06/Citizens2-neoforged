# Citizens and Yuuniverse Interactions 2.1.0

正式版 / Stable release for **Minecraft 1.21.1**, built with **Java 21** and
**NeoForge 21.1.248**. Both mods report version **2.1.0** inside their JARs.

This release promotes the current Citizens and Interactions implementation from
the dated 2.0.43 snapshot. Gameplay code is unchanged during release preparation.

## Included mods

- `citizens-2.1.0.jar`: NPCs, traits, commands, templates, persistence,
  navigation, packet NPCs and native extension interfaces.
- `yuuniverse-interactions-2.1.0.jar`: dialogue, options, actions, cutscenes,
  menus and the existing quest/commerce integrations. Requires Citizens.

Recent Citizens fixes retain template event commands across restart, validate
navigation edits before mutation, restore active virtual NPC movement, and
persist attack range/delay and path distance margin. Existing third-party
integrations remain included in these mods.

Download individual runtime JARs or `citizens-interactions-2.1.0-neoforge.zip`.
The ZIP includes both runtime mods, separate source archives, the existing
repository license, deployment notes, a manifest and SHA-256 checksums.
Economy and GriefLogger remain separate downloads from the dated snapshot
release. This release does not include a modified Paradigm binary.

## Upgrade

Stop the server cleanly and back up its current mods, configuration, worlds,
player data and databases together. Remove older JARs with mod IDs `citizens`
and `interactions`, then install the two 2.1.0 runtime JARs. Preserve existing
NPC saves, dialogue files, configuration and provider mods. Do not place source
archives in `mods/` or keep duplicate old/new mod IDs.

Use the existing Java 21 / NeoForge startup command. The selected deployment
target is NeoForge 21.1.248; retain compatible versions of any optional providers
already used by the server. Check the loaded versions and synchronize updated
hosted/client copies where the existing pack includes them.

For rollback, stop the server and restore the complete matching pre-upgrade
snapshot, including external databases where applicable.

## Validation and known limits

The prior gameplay checkpoint passed 847 ordinary tests on each of NeoForge
21.1.233 and 21.1.248 and 244 navigation persistence checks. Release preparation
builds the two 2.1.0 packages and checks their metadata, production bytecode,
archive integrity, source separation and checksums. It does not rerun gameplay
tests or start Minecraft servers.

Physical-client acceptance remains deferred. Remaining navigation scalar-default
restoration review and missing original quest/ItemEdit content retain the limits
described in the repository reports. The stable release designation does not
assert new acceptance of those outstanding scenarios.
