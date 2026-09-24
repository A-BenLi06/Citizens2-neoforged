# Native entity configuration commands

Implementation follow-up for 2026-09-14 (UTC+08:00), after `717605f`.

## Scope and comparison

The reference is Citizens2 commit `d98e55016c2df8102f37732cd19168fe8dd27e46`. These commands live in the original versioned trait classes, not only in `NPCCommands`. The port already had most of their backend traits, but those did not provide the missing configuration commands.

Added 28 command groups, exposing 30 `/npc` names including aliases:

- `allay`, `armadillo`, `axolotl`, `bee`, `camel`, `cat`, `fox`, `frog`, `goat`, `llama`.
- `mushroomcow` / `mooshroom`, `panda`, `parrot`, `polarbear`, `pufferfish`, `sniffer`, `snowman` / `snowgolem`, `tropicalfish`, `villager`.
- `areaeffectcloud`, `boat`, `enderdragon`, `phantom`, `piglin`, `shulker`, `spellcaster`, `vex`, `warden`.

The source inventory now contains 185 root/modifier pairs, up from 155 at both `f8ede13` and `717605f`. The reference has 193; seven port-only pairs mean that subtracting those totals is not a meaningful completion measure. Seven missing `/npc` names apply to 1.21.1: `text`, `bossbar`, `display`, `itemdisplay`, `textdisplay`, `interaction`, `potioneffect`. Six additional names concern later-version entities/variants, and `waypoints hpa` / `wp hpa` are separate debugging aliases. Inventory evidence is `artifacts/entity-command-inventory.json`; declarations alone do not certify behavior.

## Behavior and repairs

- Commands enforce selection, ownership, permissions and actual/cosmetic entity type. Boat/chest-boat, llama/trader-llama, and evoker/illusioner combinations are accepted. Villager shaking is allowed as a standalone action and requires a spawned villager.
- Added opt-in strict argument handling to the command API. These commands reject unknown long flags, malformed numbers/booleans/enums and nonfinite floats before running their bodies. All declared value-flag aliases participate in parsing and completion. Other commands retain the prior default behavior.
- Cat/frog variants, villager types/professions, cloud potions and simple particles resolve registered values and preserve namespaces. Tests register actual additional values rather than assuming vanilla-only registries. Unknown saved provider IDs survive load/save/copy and can be explicitly replaced.
- Fixed NPC creation defaults overwriting an already initialized `MobType` with PLAYER. Default traits now fill missing traits. API-created and unspawned NPCs retain the requested entity type, including through copying.
- Fixed panda rolling to use its rolling setter. Updating slime size now applies to an already spawned NPC without replacing authoritative health.
- Warden anger accepts a loaded entity UUID or an online player name. Dig/emerge/roar apply native poses and sounds; emerge and roar return to standing after 134 and 84 ticks. Deferred resets check the original entity and pose. Rejected targets/subcommands do not attach a trait.
- Strict armadillo input accepts the legacy `ROLLING_UP` / `ROLLING_OUT` names while the saved-data parser remains forgiving. Llama chest flags are declared, pufferfish state follows the actual 0–2 native range, and shulker peek is validated as 0–100 before conversion to a byte.

The initial cloud command supported only particles that need no additional parameters. The [September 24 native particle-options follow-up](cloud-particle-options-2026-09-24.md) adds native parameter syntax and preserves full options through save/load/copy, including API-supplied item components and provider-defined fields. Retaining an unresolved definition is not equivalent to rendering it. The legacy upstream cloud shrink-rate setter bug is not reproduced. Registry preservation cannot make an absent provider's content available.

## Verification

The dedicated isolated server passes **190 checks** on NeoForge **21.1.248**, covering command state before spawn, copying, live state, despawn/respawn, aliases, non-operator permission grants, ownership denial, invalid-input atomicity and live application of custom registered values. Cloud checks inspect native radius, shrink rate, duration, particle, potion and color. Warden reset checks wait for actual server ticks. Evidence: `artifacts/repair-entity-commands-runtime248-final.log`.

Final validation also passes 107 NPC scenario assertions, 223 existing runtime probes, 44 removal checks, 101 actual-provider/restart permission checks, 18 no-provider permission checks, 48 dialogue unit cases and 152 ordinary tests. Unit failures/errors/skips are zero. The normal build succeeds and all release jars exclude `RuntimeAudit` classes. `artifacts/entity-command-validation-summary.json` records the exact logs, counts and jar hashes.

From the repository root, prepare the fixture with `./tools/prepare-entity-command-audit.ps1`. From `neoforge`, run:

```powershell
$env:JAVA_HOME = 'C:\Users\ben_l\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2'
.\gradlew.bat '-Pneo_version=21.1.248' -I ../tools/entity-command-runtime-audit.gradle runServer --console=plain
```

This opt-in fixture binds to `127.0.0.1:25585`, requires its marker and an empty NPC registry, registers test-only content and stops itself after the checks. It operates under `artifacts/entity-command-audit-server`; ordinary release builds must exclude these probe classes. Physical-client appearance, the remaining command/API/behavior gaps and full modpack acceptance are still separate work. CmdCam remains the camera provider and Yuuniverse Economy remains the economic API provider. No production deployment is included.
