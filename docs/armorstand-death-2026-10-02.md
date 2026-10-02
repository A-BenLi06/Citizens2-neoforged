# Armor-stand destruction and complete native drops

Armor stands break through their own `hurt` and `kill` methods. They do not normally call `LivingEntity.die`. The previous change preserved their NPC drop notification, but breaking still left the controller holding a removed entity. Equipment and the stand item also bypassed the mutable death-drop list because vanilla emits them with `Block.popResource` after/beside `dropAllDeathLoot`.

## Native lifecycle

The armor-stand hurt wrapper owns a potential death until the native method returns. Nonlethal hits leave it unaccepted. Native break/kill boundaries accept it, preserving the original return values and the distinct survival, explosion, fire, creative and bypass/direct-kill paths. The controller is released after native processing; ordinary Citizens/Sentinel scheduling remains available while the NPC is despawned. Virtual stands remove their existing viewer entity immediately without a living-mob death animation.

Creative attacks and direct/bypass kills retain native no-loot behavior. They notify the NPC lifecycle once without inventing a `LivingDropsEvent` or admitting trait-added loot. Repeated kill calls on the retired entity do not create another NPC death. Callbacks that replace or destroy the NPC retain ownership of their result, and obsolete timers cannot replace it.

## Complete drop event

For loot-producing breaks, native item generation still runs. The ordinary living-drop hook collects its generated items while the armor-stand method finishes emitting equipment and its optional stand item. At the native kill boundary, one combined `LivingDropsEvent` offers all those items through the existing mutable `NPCDeathEvent` contract. `DROPS_ITEMS`, trait additions, list replacements and native cancellation therefore apply to the complete list.

Each individual `Block.popResource` call uses NeoForge's existing captured-drop list under a scoped accessor. This preserves native positions, velocity, stack metadata, pickup delay, block-drop gamerules and snapshot restoration policy. The previous capture is restored before callbacks run. Accepted original block items retain their surrounding capture destination; ordinary living/added items retain normal level admission. The code does not recreate block-item generation or globally intercept unrelated item spawns.

Vanilla armor stands keep their normal path. Ordinary Citizens living/player deaths retain their existing event, animation and respawn behavior.

## Validation and remaining work

`artifacts/armorstand-death-negative248.log` reproduces the retained-controller failure. The initial implementation passes 847 checks; the expanded native-route/drop matrix passes 1,610. The final suite passes 1,639 checks on both NeoForge 21.1.248 and the project's default 21.1.233. Conditional per-item assertions can vary with native random loot in the ordinary mob controls.

The fixture covers real survival first/second hits, explosion/fire, creative/bypass/direct kills, full hand/armor/stand drops, default suppression, trait additions, replacement, cancellation, nested native block capture, the block-drop gamerule, virtual removal packets, exact respawn deadlines and lifecycle callback replacement/destruction. It also includes a vanilla armor-stand control. Reproduction uses [the death runtime audit](../tools/death-runtime-audit/README.md).

Final regression, ordinary test/build, source checkpoints and archive evidence are recorded in `artifacts/armorstand-death-validation-summary.json` and the root walkthrough. Interactions remains a separate, unchanged mod/JAR.

Final regression passes 401 armor-stand pose, 78 packet interaction, 43 packet viewer and 62 removal checks, plus 107 NPC assertions and 223 general runtime probes. All 232 ordinary tests and a clean build pass on the default NeoForge version. Binary/source archives contain the new production hooks and exclude audit fixtures; Interactions has exactly the same archive entry contents as before this change. The original Citizens save remains unchanged. No deployment or physical-client acceptance is implied by these results.

Dragon death remains separate work: lethal damage can restore health to one and enter the native dying flight phase, followed by a 200-tick death sequence; direct `kill` has another path. Replacing the ordinary visual deadline with a constant alone would not preserve this lifecycle, especially for virtual dragons that receive no native world ticks. The [current checklist](remaining-citizens-work-2026-09-26.md) retains this requirement, the remaining commands/data/integrations and physical-client/modpack/proxy acceptance.
