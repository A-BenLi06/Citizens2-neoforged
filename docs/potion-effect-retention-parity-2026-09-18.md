# Retaining potion effects when their provider is unavailable

Reference: Citizens `d98e55016c2df8102f37732cd19168fe8dd27e46`, `PotionEffectsTrait` and the existing potion-effect persistence format. Target: Minecraft 1.21.1 / NeoForge 21.1.248 with JDK 21. This follows local implementation `c14196e`; the full work-start baseline remains `f8ede13`.

## Corrected data loss and management

The port previously stored named effects directly as `Map<String, MobEffectInstance>`. When a saved type was unavailable in the native registry, the persister returned null and the map loader omitted the entry. The next save permanently removed that definition, even if the effect's mod was only temporarily absent.

The trait now uses the existing `@Persist(reify = true)` / `Persistable` mechanism to serialize its named-effect store. The file layout remains `traits.potioneffects.persistent.<literal name>` with the original type, duration, amplifier, ambient, particles and icon fields. No global persistence-loader behavior was changed.

Available entries still become native MobEffectInstance templates. Unavailable entries retain an independent deep copy of their raw definition, including dotted/Unicode names, unrecognized fields, nested collections and malformed records that cannot be instantiated. They survive snapshots, NPC copies, file save/load and respawn without applying a substitute effect. Loading new state replaces old resolved and unresolved state.

`/npc potioneffect list` includes unavailable names and their stored type IDs using a resource-based message. Removal works for both resolved and unresolved names. Replacing a name with a valid effect removes its previous unavailable definition; invalid replacement commands preserve it. The mutable native map API remains available for resolved templates, including compute/put replacements. `hasPersistentEffect`, `removePersistentEffect` and the read-only `getUnresolvedEffectTypes` snapshot cover unavailable names explicitly. Removing all definitions does not leave stale entries in saved data.

When the provider is present on the next load, the original registry ID resolves normally and the retained six standard fields produce the actual native effect. Spawn/respawn applies a copy so native duration ticking does not drain the saved template. Provider registry changes are picked up through the normal load/startup lifecycle, not by polling a frozen registry every tick.

## Evidence

The isolated entity-command fixture on `127.0.0.1:25585` now supports two consecutive launches. The first does not register the returning test effect and saves an NPC carrying its definition. The second registers that exact ID through NeoForge's RegisterEvent, loads the first process's file and checks the resolved parameters, native application and respawn. The recovery phase requires the previous file; it does not recreate the missing definition.

Run from `neoforge` after preparing `tools/prepare-entity-command-audit.ps1`:

```powershell
./gradlew.bat '-Pneo_version=21.1.248' -I ../tools/entity-command-runtime-audit.gradle runServer --console=plain
./gradlew.bat '-Pneo_version=21.1.248' '-Peffect_audit_provider=true' -I ../tools/entity-command-runtime-audit.gradle runServer --console=plain
```

The fixture also checks literal keys, nested raw data, independent input/output snapshots, known/unknown mixtures, no fallback effect, actual YAML resaving, list message packets, invalid/valid replacements, native-map mutations and state replacement. Two test-infrastructure corrections were necessary: YamlStorage intentionally represents lists as indexed maps internally, so file fidelity is checked on parsed YAML before/after resaving; the old synthetic player suppressed system messages, so it now uses the real packet path for list assertions.

The returning provider is a test registration, not a claim that every effect from the full server modpack is installed or client-tested. Unknown definitions remain inactive until their exact types are available. This change cannot reconstruct data already deleted by an older save. Full text/item rendering, API/command behavior, conversations, quests, groups/temporary permissions, Sentinel and client/modpack acceptance remain separate work. CmdCam owns camera playback and Yuuniverse Economy owns economic APIs. No production deployment or data migration was performed.

Validation passes on NeoForge 21.1.248: 308 entity-command checks with the returning provider absent and 313 with it present (overlapping runs retaining the previous 285 cases), 107 general NPC assertions, 223 runtime probes, 44 removal/undo checks and 166 ordinary unit tests. Unit failures/errors/skips are zero. The normal build passes and its jars exclude runtime probes. Logs, completion markers and SHA-256 hashes are in `artifacts/effect-retention-validation-summary.json`; the earlier pickup/sleep follow-up remains the evidence for unrelated dedicated suites.
