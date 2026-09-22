# Saved item provider audit

This opt-in dedicated server fixture uses loopback port 25588 and an empty world under `artifacts/saved-item-audit-server`. It loads copies of the actual NetMusic, Refurbished Furniture, Framework, Immersive Vehicles and Prosperous jars. Production configuration/worlds are not used.

Create independent expected NBT values with Python and PyYAML, then prepare the fixture with local provider files:

```powershell
py -3.13 -X utf8 tools/inspect-saved-item-payloads.py '<original ItemEdit server-database.yml>' artifacts/legacy-saved-item-payloads.json
.\tools\prepare-saved-item-audit.ps1 -ProviderJars @('<netmusic.jar>', '<furniture.jar>', '<framework.jar>', '<vehicles.jar>', '<prosperous.jar>') -SavedItemDatabase '<original ItemEdit server-database.yml>' -ExpectedPayloads artifacts/legacy-saved-item-payloads.json
Set-Location neoforge
.\gradlew.bat '-Pneo_version=21.1.248' -I ../tools/saved-item-runtime-audit.gradle runServer --console=plain
.\gradlew.bat '-Pneo_version=21.1.248' -I ../tools/audit-tests.gradle test --console=plain
.\gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

Use JDK 21. The prepared reference contains 140 saved definitions, with 13 internal payloads. The current fixture intentionally has no EyeMod provider: item 163 must be unavailable because its package contains `eyemod:eyephone`. The other twelve payload-bearing items are available. Exact source/provider hashes belong to `artifacts/saved-items-validation-summary.json` and `artifacts/saved-items-provider-manifest.json`.

The 72 checks verify the actual NetMusic song accessor, the vehicle mod's public NBT interface, furniture package accessors, sender/message metadata, nested item aliases/lore/counts, sparse/multiple/nested package slots, malformed/duplicate/unavailable children, preservation of unknown data, native save/load and network codecs, successful dialogue rewards, failure before payment, actual package opening and template independence. The Gradle gate requires `[SAVEDITEMAUDIT] COMPLETE`, rejects its failure marker and limits the run to four minutes. A normal build removes probes; inspect release jars before delivery.

The optional maid integration recipe in the installed NetMusic jar logs an unavailable `touhou_little_maid:altar_recipe_serializers` entry in this fixture; the optional maid mod is not installed here. This does not supply or certify maid/music playback integration. These checks do not play remote music, render a client, refuel a vehicle, or exercise the complete production modpack.
