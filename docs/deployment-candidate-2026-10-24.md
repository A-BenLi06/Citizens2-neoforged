# Private deployment candidate — 2026-10-24

Target: Windows 10, Java 21, Minecraft 1.21.1, NeoForge **21.1.248**.
This is a replacement-mod bundle for the existing server pack, not a complete
server distribution. No world, player data, credentials or test configuration is included.

| Mod ID | Included version | Action |
|---|---|---|
| citizens | 2.0.43-neoforge-SNAPSHOT | Replace every older Citizens JAR |
| interactions | 2.0.43-neoforge-SNAPSHOT | Replace every older Interactions JAR |
| yuuniverse_economy | 0.5.9-SNAPSHOT | Replace old Economy JAR, including an `economy.jar` alias |
| grieflogger | 1.2.10-1.21.1-yuuniverse.10 | Replace older GriefLogger builds |
| paradigm | 2.5.0, private hologram reader fix | Replace original Paradigm; do not keep both |

Citizens/Interactions are freshly assembled from source checkpoint `d0a7815`.
Economy, GriefLogger and Paradigm reuse the exact latest adopted binaries whose
hashes match the retained acceptance manifests. The later GriefLogger lookup
prototype is excluded. Paradigm retains its 2.5.0 descriptor; distinguish the
private repair by SHA-256. Keep this candidate private.

## Maintenance deployment

1. Verify `SHA256SUMS.txt` against extracted files. The adjacent `.zip.sha256`
   pins the complete ZIP. `manifest.json` identifies each replacement, source
   checkpoint and dependency. Keep the current compatible provider pack; its
   selected 64-archive reference inventory is included in the manifest. Only
   the five replacement JARs are shipped. AutoModpack is absent from that
   historical reference; this package does not change pack distribution.
2. Stop the actual server normally and prevent automatic restart during the
   operation. Take a consistent backup of the entire server directory, including
   mods, config, serverconfig, all dimensions/world/player data and mod storage.
   Take a matching native backup of any external MySQL database used by
   GriefLogger. Preserve the actual pre-upgrade state at maintenance time.
3. Move the old JARs for the five IDs outside `mods/`. Inspect IDs rather than
   relying on filenames: Economy can be named `economy.jar`. Copy only this
   bundle's `mods/*.jar` into the server's `mods/`. Leave all other providers,
   configs, NPC saves, dialogues, quests, balances and databases in place.
   Source archives belong outside `mods/`.
4. Use the existing NeoForge 21.1.248/Java 21 startup command. GriefLogger .10
   can migrate the supported history schema on startup; retain its database
   backup. Review startup errors and confirm these five versions loaded once.
   GriefLogger requires Architectury >=13.0.8 and
   SuperMartijn642's Config Lib >=1.1.8 from the existing pack. Economy requires
   NeoForge >=21.1.247, so the 21.1.233 development baseline is not this stack's target.
5. Synchronize hosted/client copies of updated mods where the existing pack
   includes them, then perform the operator's ordinary-client check when ready.
   No physical-client acceptance is claimed by packaging.

Do not apply the older prepared Economy 0.5.0 package over this candidate.
That package includes a separate shop-presentation import. This bundle upgrades
the current application and preserves current data; it does not import legacy
shops or repair absent original quest mappings / ItemEdit definitions 6, 15 and
144. Original source inputs remain necessary for that separate content work.

## Rollback

Stop the server before rollback. Restore the complete matching pre-upgrade
snapshot and external database backup together, then use the original startup
command. Restoring only old JARs after a schema upgrade or restoring economy
independently of player/quest state is not a consistent rollback. New gameplay
after cutover will be lost if the earlier snapshot is restored.

## Build and provenance

The fresh build ran `assemble dialogueJar` with NeoForge 21.1.248, without tests
or native-server startup. Archive inspection checks CRCs, descriptors, versions,
namespace separation and absence of fixture classes. Existing Citizens evidence
is 847 ordinary tests per platform and 244 navigation persistence checks; the
provider evidence predates this package. These are retained results, not new
full-stack or physical-client acceptance. Remaining navigation scalar-default
review is described in the navigation settings report. Packaging does not claim
completion of every Citizens function.

The `reference/` directory contains the release specification, build log and
bounded acceptance reports. Source archives are in `sources/`. The manifest
records exact bytes because SNAPSHOT filenames alone do not identify a build.

To reassemble from the pinned local inputs, use Python 3.11 or later:

```powershell
python tools/package-deployment-candidate.py --output artifacts/<new-candidate-directory>
```

The packager requires new output paths, verifies pinned input hashes and never
starts or modifies a server. Actual activation is a separate maintenance action.
