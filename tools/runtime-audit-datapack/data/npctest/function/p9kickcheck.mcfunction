# Measure centre displacement, not intersection with a selector box (dx=1 spans two blocks, plus entity bounds).
# The source is directly north, so the push is along positive Z. Disabled must stay within 0.01 blocks; control must
# move at least 0.1 blocks. Both must still exist, and p9apply separately asserts that both damage calls succeeded.
execute store result score #p9_delta_disabled npctest run data get entity @e[type=minecraft:pig,name=P9KickBob,limit=1] Pos[2] 1000
execute store result score #p9_delta_control npctest run data get entity @e[type=minecraft:pig,name=P9NoKickBob,limit=1] Pos[2] 1000
scoreboard players operation #p9_delta_disabled npctest -= #p9_start_disabled npctest
scoreboard players operation #p9_delta_control npctest -= #p9_start_control npctest
execute if entity @e[type=minecraft:pig,name=P9KickBob] if score #p9_delta_disabled npctest matches -10..10 run say [NPCTEST] PASS knockback-disabled-held-still
execute unless score #p9_delta_disabled npctest matches -10..10 run say [NPCTEST] FAIL knockback-disabled-held-still
execute if entity @e[type=minecraft:pig,name=P9NoKickBob] if score #p9_delta_control npctest matches 100.. run say [NPCTEST] PASS knockback-control-was-pushed
execute unless entity @e[type=minecraft:pig,name=P9NoKickBob] run say [NPCTEST] FAIL knockback-control-missing-for-displacement
execute unless score #p9_delta_control npctest matches 100.. run say [NPCTEST] FAIL knockback-control-was-pushed
# Both alive, or neither assertion above means anything.
execute if entity @e[type=minecraft:pig,name=P9KickBob] run say [NPCTEST] PASS knockback-npc-survived
execute unless entity @e[type=minecraft:pig,name=P9KickBob] run say [NPCTEST] FAIL knockback-npc-survived
execute if entity @e[type=minecraft:pig,name=P9NoKickBob] run say [NPCTEST] PASS knockback-control-survived
execute unless entity @e[type=minecraft:pig,name=P9NoKickBob] run say [NPCTEST] FAIL knockback-control-survived
# The skinned player NPC spawned and survived the skin request. A rendered skin cannot be seen from a datapack; the
# persisted skin name is checked in saves.yml after the run, and the visual needs the manual acceptance pass.
# Selected by position, not by name: a player NPC's name lives in its GameProfile rather than in CustomName, so
# @e[type=minecraft:player,name=...] never matches one. (Which is also why /npc skin has to be reached through
# "npc select --name" rather than a selector.)
execute if entity @e[type=minecraft:player,x=165,y=-62,z=61,dx=2,dy=4,dz=2] run say [NPCTEST] PASS skin-player-npc-spawned
execute unless entity @e[type=minecraft:player,x=165,y=-62,z=61,dx=2,dy=4,dz=2] run say [NPCTEST] FAIL skin-player-npc-spawned
say [NPCTEST] p9 batch end
