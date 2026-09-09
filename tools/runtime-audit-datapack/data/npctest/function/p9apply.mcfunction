# mob_attack is not in the no_knockback damage tag. One damage leaves these ten-health pigs alive.
execute store result score #p9_start_disabled npctest run data get entity @e[type=minecraft:pig,name=P9KickBob,limit=1] Pos[2] 1000
execute store result score #p9_start_control npctest run data get entity @e[type=minecraft:pig,name=P9NoKickBob,limit=1] Pos[2] 1000
execute store success score #p9_damage_disabled npctest run damage @e[type=minecraft:pig,name=P9KickBob,limit=1] 1 minecraft:mob_attack at 158 -60 56
execute store success score #p9_damage_control npctest run damage @e[type=minecraft:pig,name=P9NoKickBob,limit=1] 1 minecraft:mob_attack at 162 -60 56
execute if score #p9_damage_disabled npctest matches 1 if score #p9_damage_control npctest matches 1 run say [NPCTEST] PASS knockback-damage-applied
execute unless score #p9_damage_disabled npctest matches 1 run say [NPCTEST] FAIL knockback-disabled-damage-refused
execute unless score #p9_damage_control npctest matches 1 run say [NPCTEST] FAIL knockback-control-damage-refused
schedule function npctest:p9kickcheck 40t
