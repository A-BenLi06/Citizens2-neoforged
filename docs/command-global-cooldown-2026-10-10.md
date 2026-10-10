# Global NPC command cooldown acceptance

The existing `/npc command add ... --gcooldown 1h` path already stores and enforces
its duration. The builder assigns its `cooldown` argument to the `globalCooldown`
field correctly. A suspected setter defect was disproved by running the new
native scenario before changing production code.

The creation fixture now uses two connected mock players and ordinary/packet NPCs
to verify the command through the native dispatcher and scheduler:

- The first player pays the per-command experience cost and receives one reward.
- A second player is blocked by the shared cooldown without paying that command's cost.
- The configured duration and last-use timestamp survive trait serialization and replacement.
- Restored active cooldowns still reject use; an expired saved timestamp permits it.
- Clearing global history and granting the explicit bypass permission each permit the next command.
- A different NPC with the identical command text owns independent cooldown state.

Default per-player cooldown history is cleared only in the reset/bypass probes so
that it cannot mask the global gate being exercised. Expiry is tested by restoring
an expired timestamp rather than waiting an hour or changing the production clock.
The fixture retains both player connections for packet pumping and cleanup.

The unchanged gameplay implementation passes 546 creation checks on NeoForge
21.1.233 in `artifacts/command-global-cooldown-before.log` and on 21.1.248 in
`artifacts/command-global-cooldown-native-248.log`. This is positive
acceptance evidence, not a failing negative control or a production-code fix.
This scenario covers per-command costs; it does not establish the broader policy
for global click costs, delayed commands, external providers or physical clients.
