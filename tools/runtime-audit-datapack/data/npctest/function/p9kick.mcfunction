# Entity section loading can finish after the chunk becomes ENTITY_TICKING. Wait for both actors before applying damage.
scoreboard players set #p9_wait npctest 0
scoreboard players set #p9_ready npctest 0
function npctest:p9wait
