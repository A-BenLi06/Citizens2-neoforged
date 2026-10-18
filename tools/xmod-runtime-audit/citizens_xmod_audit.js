const CitizensScriptApi = Java.loadClass('net.citizensnpcs.api.integration.CitizensScripts')
const XModAudit = Java.loadClass('net.citizensnpcs.audit.XModRuntimeAudit')
const FtbQuestObject = Java.loadClass('dev.ftb.mods.ftbquests.quest.QuestObjectBase')
const Arrays = Java.loadClass('java.util.Arrays')
const taskId = FtbQuestObject.getCodeString(0x20103)
const rewardId = FtbQuestObject.getCodeString(0x20104)

ServerEvents.recipes(event => {
    event.shapeless(Item.of('minecraft:paper', 4), ['minecraft:stone']).id('citizens_audit:xmod_recipe')
})
FTBQuestsEvents.customTask(taskId, event => {
    event.setMaxProgress(4)
    event.setCheckTimer(1000000)
    event.setEnableButton(false)
    event.setCheck((data, player) => {
        const state = CitizensScriptApi.task(player, taskId)
        const recipe = CitizensScriptApi.recipe(player, 'citizens_audit:xmod_recipe').orElseThrow()
        if (state.maximum() != 4 || recipe.output().getCount() != 4) throw new Error('Public script snapshot mismatch')
        // The evidence service supplies progress; querying never fabricates a completed craft.
        XModAudit.note('check', taskId, player.uuid.toString(), data.getProgress())
    })
    XModAudit.note('customTask', taskId, '', event.task.getMaxProgress())
})
FTBQuestsEvents.started(taskId, event => {
    XModAudit.note('started', taskId, event.player == null ? '' : event.player.uuid.toString(),
        event.data.getData().getProgress(event.object))
})
FTBQuestsEvents.completed(taskId, event => {
    XModAudit.note('completed', taskId, event.player == null ? '' : event.player.uuid.toString(),
        event.data.getData().getProgress(event.object))
})
FTBQuestsEvents.customReward(rewardId, event => {
    const player = event.player
    const state = CitizensScriptApi.task(player, taskId)
    XModAudit.note('customReward', rewardId, player.uuid.toString(), state.progress())
    // Native item reward exercises this callback only; monetary claims use a separate durable contract.
    player.give(Item.of('minecraft:diamond', 1))
    CitizensScriptApi.validateEconomyMenu(player, Arrays.asList('browser', '0'))
    CitizensScriptApi.openEconomyMenu(player, Arrays.asList('browser', '0'))
})
