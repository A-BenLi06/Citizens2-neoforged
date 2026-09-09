scoreboard players add #p9_wait npctest 1
execute if entity @e[type=minecraft:pig,name=P9KickBob] if entity @e[type=minecraft:pig,name=P9NoKickBob] run scoreboard players set #p9_ready npctest 1
execute if score #p9_ready npctest matches 1 run function npctest:p9apply
execute if score #p9_ready npctest matches 0 if score #p9_wait npctest matches ..199 run schedule function npctest:p9wait 1t
execute if score #p9_ready npctest matches 0 if score #p9_wait npctest matches 200.. run say [NPCTEST] FAIL knockback-actors-ready-timeout
