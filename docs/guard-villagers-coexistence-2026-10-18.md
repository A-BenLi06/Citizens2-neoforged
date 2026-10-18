# Guard Villagers ownership and native workflows

The optional adapter targets Guard Villagers 2.4.12 for Minecraft 1.21.1, pinned
by `docs/ecosystem-mods-1.21.1.lock.json`. Citizens NPCs keep their identity when
players sneak-click eligible villagers with a recruitment weapon. The adapter
skips only Guard's recruitment callback for owned entities; it does not cancel
the shared NeoForge interaction event. Ordinary NPC click listeners and native
recruitment of ordinary villagers remain available.

Guard's spawn callback adds goals after Citizens has removed native AI. The
adapter skips that callback for NPCs with `useMinecraftAI=false`. Its assistance
callbacks also directly assign targets to nearby iron golems, independently of
goal selectors. Those assignments now respect the same ownership boundary.
Named registries and packet entities use the actual entity-to-NPC association.
An NPC explicitly spawned with native AI enabled retains provider goals and
assistance. Native AI opt-in does not permit replacement of the NPC's identity.

Ordinary `guardvillagers:guard` entities remain Guard Villagers' responsibility:
recruitment, inventory menu, equipment, follow owner, patrol anchor, reputation,
combat and native persistence. No Guard AI is attached to Citizens entities and
this adapter makes no Sentinel parity claim. Citizens' existing targetability
and protection rules apply when a guard targets or damages an NPC. Guard's
scoreboard alliance, owner exclusions and configured friendly-fire policy remain
in effect. Allowing damage to an NPC requires its Citizens protection policy to
allow damage too; enabling Guard friendly fire does not grant that permission.

Validation uses `tools/prepare-guard-audit.ps1` and the opt-in
`tools/guard-runtime-audit.gradle` fixture at loopback port 25630. It recruits an
ordinary villager through the native event handler, observes real NPC clicks,
exercises target/damage assistance, opens the native guard equipment container,
round-trips equipment/owner/follow/patrol NBT, and invokes the native decoded
follow/patrol payload handlers with a real ServerPayloadContext. Normal server
ticks run the provider's follow and patrol goals and verify physical movement.
The fixture does not replace native goals or invoke private provider methods.
The provider delays patrol admission until over 200 world ticks have elapsed and
can run its random stroll goal concurrently. Movement checks allow that native
delay and verify decreasing distance while the patrol goal runs; they do not
assert uninterrupted or exact arrival at the anchor. The fixture clears retained
ordinary mobs and disables random spawning so a failed prior run's attackers
cannot turn a patrol exercise into an unobserved combat exercise.

The packet-handler checks verify the server contract, not physical-client
authentication, rendering or screen buttons. Equipment NBT roundtrip does not
establish a full-world restart guarantee. Physical client, combined modpack,
arbitrary third-party AI overrides and migration acceptance remain separate
ADAPT-08/11 gates. Production was not restarted or changed. Exact validation
counts and provider/release/log hashes are retained in
`artifacts/guard-coexistence-validation-2026-10-18.json`.
