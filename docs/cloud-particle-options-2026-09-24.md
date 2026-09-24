# Native area-effect-cloud particle options

Validated: 2026-09-24 19:52:18 +08:00 (UTC+08:00), Minecraft 1.21.1 / NeoForge 21.1.248.

Area-effect-cloud NPCs now accept and preserve native particle options. Previously the command rejected every parameterized particle, while the public `setParticle(ParticleOptions)` API accepted them but saved only the particle type ID. Copying or reloading a dust, block or item particle consequently lost its required data.

## Parsing and persistence

The command and trait loader use Minecraft 1.21.1's `ParticleArgument.readParticle` with the server's registry lookup. Each registered particle type owns its parameter codec and validation. The parser consumes the complete value, rejecting trailing arguments. Legacy bare names and case-insensitive IDs remain supported; only the ID is normalized, preserving case-sensitive block-state keys and item component text.

The trait's existing `particle` string now stores native `id{options}` syntax. `ParticleTypes.CODEC` supplies the type and all option fields; no list of particle-specific fields or Bukkit compatibility types is introduced. Simple particles retain their plain namespaced ID. Available values resolve on normal load; invalid or unavailable definitions retain their original string exactly and apply no substitute particle. Explicit replacement or clearing removes the retained unavailable value.

Examples, quoting the entire value when it contains spaces:

```text
/npc areaeffectcloud --particle flame
/npc areaeffectcloud --particle 'dust{color:[0.25,0.5,1.0],scale:1.75}'
/npc areaeffectcloud --particle 'block{block_state:{Name:"minecraft:oak_log",Properties:{axis:"x"}}}'
/npc areaeffectcloud --particle 'item{item:"minecraft:paper"}'
```

The same serialization supports API-supplied item components and provider-defined particle fields. Native syntax and codec constraints remain authoritative. Preserving an absent definition does not render it, and already lost options cannot be reconstructed from an old bare ID.

## Reproduction

Prepare `tools/prepare-entity-command-audit.ps1` from the repository root. The opt-in fixture binds to `127.0.0.1:25585` and requires its own empty registry. From `neoforge`, run sequentially:

```powershell
$env:JAVA_HOME = 'C:/Users/ben_l/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2'
./gradlew.bat '-Pneo_version=21.1.248' '-Peffect_audit_provider=false' -I ../tools/entity-command-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' '-Peffect_audit_provider=true' -I ../tools/entity-command-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' '-Peffect_audit_provider=false' -I ../tools/entity-command-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' test build --console=plain
```

The optional fixture provider registers a parameterized particle using NeoForge's actual registration event. The absent phase writes a definition to `cloud-particle-audit/provider.yml`; recovery requires the file and resolves its amount and mixed-case label. The final absent phase checks the provider's resaved definition survives another load/save with no substitute. Ordinary builds exclude the fixture provider and audits.

The cases cover native simple/dust/color-transition/block/item/shriek values, live commands, trait serialization, NPC copies, spawning, native stream encoding, item components and text case, invalid input before mutation, raw unavailable retention, replacement and clearing. Client particle rendering and the full modpack remain separate acceptance work. The retained providers and production deployment follow the [ecosystem scope](neoforge-ecosystem-replacements-2026-09-22.md); no production changes are included.

## Validation

The corrected three-process sequence passes **375 / 382 / 375** entity-command checks for absent/present/absent-again. These overlapping totals include **67 / 69 / 67** new cloud-particle checks and the preceding entity/presentation/effect cases. Provider snapshots after recovery and subsequent absence have identical SHA-256 `4ae90902ffcd90429924320e3bea5ed9e9121149b227ce581880d3a20c5f21e4`.

The first fixture incorrectly checked file existence after constructing `YamlStorage`, whose constructor creates the file. Its initial absent phase therefore retained an empty sample, and recovery failed. The corrected fixture records existence before construction, requires the preceding file in recovery and asserts the original ID/amount/mixed-case label. All three phases were rerun. The initial logs and empty sample are retained as diagnostics; that initial absent result is not recovery evidence. No production behavior changed for this fixture correction.

Sequential general regression passes **107 NPC assertions and 223 probes**. Normal `test build` passes **217 ordinary tests** with zero failures/errors/skips. Each Gradle process was terminal before the next began; final batch 5045 exited 0 and no audit task remains. Release/source jars exclude all fixture/provider classes and include the modified production classes.

Citizens SHA-256: `76e86a2e712ac58f725f0a004b3afdeb6fde126a98519fc38ec3a44ae92097a1`; sources: `78c54c9b7a7b254b781ec21805153fb53c2fd722979ea828e07d8bd9f0de1bfa`. Interactions remains `d9dabf55fefb64b3c34ca7cb485ded8dcd406573513694e476ee2021ef1e5834`. Original Citizens saves.yml remains `cc99c930f8f5fbc49fe0c5eaf76002e1d19c45248304522b7ad1e83354dc1132`.

Evidence: `artifacts/cloud-particles-validation-summary.json`, generated by `artifacts/summarize-cloud-particles.ps1`, contains source/reference/fixture/log/report/jar and provider snapshot hashes. `artifacts/cloud-particles-native-source.txt` records inspected native sources and their archive hash. Unrelated dedicated suites retain their earlier evidence. No production deployment or default NeoForge version change is included; the broader goal remains active.
