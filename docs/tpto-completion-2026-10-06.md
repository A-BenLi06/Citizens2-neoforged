# Confirmed tpto completion

`/npc tpto` previously sent success immediately after requesting a teleport, including
when an NPC teleport event or native player dimension transfer was canceled.

NPC sources now use the existing completion-aware teleport API. Success feedback is
sent only for the current NPC entity and a still-valid command source. Live entity-to-NPC
lookup preserves named/anonymous registry identity. Ordinary player transfers use the
native teleport result through EntityUtil and report success only when it returns a moved
entity. Destination snapshots use the existing location validation. Target argument forms
and the tpto permission remain unchanged.

The isolated relocation fixture exercises regular and player-shaped NPCs with and
without packet transport, selected from a named registry. Every cancellation preserves
location without feedback; every completed move reports once. Explicit native-player
source tests cover canceled and successful cross-world transfer, alongside the existing
spawn, relocation, mount-completion and source-lifecycle regressions.

These checks do not establish physical-client rendering or arbitrary third-party teleport
provider behavior. Interactions remains an independent NeoForge JAR.
