# Swap-hand dialogue entry

Target: Minecraft 1.21.1. Validation uses NeoForge 21.1.248; the declared default remains 21.1.233.

Set `conversation_start_click_type: SWAP_HAND_CLICK` in `config/interactions/config.yml` and reload Interactions to start a conversation by looking at an NPC and using the player's swap-hands key. The existing right-click modes and proximity settings remain available.

## Original behavior and native implementation

The original Interactions 2.14.1 handler invokes conversation admission and cancels the hand swap only when admission succeeds. It does not use the swap to skip an active conversation. Its Citizens target lookup searches four blocks. The inspected original jar has SHA-256 `ce4824a2bb4317eb9acd40cd4388eebedfab521d96310bf74f2d26f0ac85293e`; retained bytecode is in `artifacts/swap-hand-legacy-bytecode.txt` and `artifacts/swap-hand-legacy-targeting.txt`.

The native implementation uses `LivingSwapItemsEvent.Hands` after ordinary listeners. It preserves prior cancellation and native proposed stacks, and cancels only for a newly admitted session. Permission, cooldown, readable progress, ground and conversation-radius checks remain authoritative. NPC ID bindings take precedence over name bindings. A swap during an existing conversation neither restarts nor skips it.

Targeting uses the player's current yaw/pitch, native outline clipping and native world-entity ray selection. LivingEntity head yaw can lag behind the latest move packet, so it is not used for player aim. The same ray also includes paired packet NPCs, including passengers, which are absent from native world lookup. Hidden or unpaired NPCs are excluded; ordinary entities and the nearest unconfigured NPC obstruct targets behind them. Redirects resolve through current, visible Citizens owners and reject cycles.

Admission rechecks player identity, connection, target/entity ownership, view, settings and conversation after provider callbacks. A per-player guard bounds recursive swap handling. Spatial snapshots and final block clipping reject a ray invalidated by callback movement, replacement or new occlusion. A fresh input can retry against the resulting state.

## Validation

The isolated fixture on `127.0.0.1:25621` passes 139 checks. Synthetic players enter the real PlayerList, acknowledge native chunk/teleport packets, and send input through the actual player-action and look packet handlers. Native outbound encoding remains active. Coverage includes all input modes, sneaking, ID/name lookup, permission/cooldown/ground/radius admission, world and virtual targeting, occlusion, hidden/unpaired viewers, redirect cycles, reentry, target replacement, reload and retired player connections. Accepted starts preserve hands; refused starts retain native swapping and listener-provided stacks.

The first candidate reproduced stale head-yaw selection. The following fixture run needed teleport acknowledgement before its look packet could be accepted. A separate negative control reproduced a visibility callback adding a wall after ray calculation. Those logs are retained separately from the passing 124-, 134- and final 139-check runs.

Final sequential regression passes 100 dialogue unit tests, 542 dialogue runtime checks, 78 packet-interaction checks, 107 NPC assertions and 223 general probes. All 232 ordinary tests and clean build pass. Source checkpoints, XML reports, logs, archive contents and hashes are verified by `artifacts/summarize-swap-hand.ps1` and recorded in `artifacts/swap-hand-validation-summary.json`. Citizens binary/source archive entry contents match the preceding command-options release; the Interactions jar contains the new trigger and targeting classes. Audit fixtures are excluded from release jars.

See [the fixture instructions](../tools/swap-hand-runtime-audit/README.md) for reproduction. Physical-client key input, prediction, mounted presentation, full modpack/provider acceptance and actual proxy arrival remain separate acceptance work. This batch is local and has not been deployed. NPC death/respawn and the remaining command groups stay in [the current checklist](remaining-citizens-work-2026-09-26.md).
