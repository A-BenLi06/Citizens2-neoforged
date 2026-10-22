package net.citizensnpcs.audit;

import static net.citizensnpcs.audit.FinancialBackupRuntimeAudit.trade;
import java.util.List;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.reward.*;
import net.citizensnpcs.util.*;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.yuuniverse.economy.api.*;

/** Native automatic/repeat claims and unrelated-provider behavior at the admission boundary. */
final class CommerceRewardAdmissionControls {
    private static int passed;
    static int run(ServerPlayer actor, ServerQuestFile file, EconomyApi economy, String shop, List<Reward> claimed) throws Exception {
        var server=actor.getServer();var source=actor.createCommandSourceStack().withPermission(2);
        var service=CommerceQuestTasks.current(server);var api=economy.commerceApi().orElseThrow();
        var team=file.getTeamData(actor).orElseThrow();var currency=economy.defaultCurrency();
        long balance=economy.balance(actor.getUUID(),currency.id());
        var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
        var unrelated=new Quest(file.newID(),chapter);unrelated.onCreated();var unrelatedTask=new CustomTask(file.newID(),unrelated);unrelatedTask.onCreated();
        var unrelatedReward=new ItemReward(file.newID(),unrelated,new ItemStack(Items.LAPIS_LAZULI));unrelatedReward.onCreated();
        file.clearCachedData();file.clearCachedProgress();team.setProgress(unrelatedTask,1);
        YuuniverseEconomyApi.clear(economy);
        try {
            for(var reward:claimed)team.claimReward(actor,reward,false);
            team.claimReward(actor,unrelatedReward,false);
        } finally {YuuniverseEconomyApi.publish(economy);}
        check(actor.getInventory().countItem(Items.LAPIS_LAZULI)==1 && actor.getInventory().countItem(Items.DIAMOND)==1
                && actor.getInventory().countItem(Items.EMERALD)==1 && economy.balance(actor.getUUID(),currency.id())==balance,
                "already_claimed_and_unrelated_native_rewards_preserve_provider_outage_semantics");

        var automatic=new Quest(file.newID(),chapter);automatic.onCreated();var autoTask=new CustomTask(file.newID(),automatic);autoTask.onCreated();
        var autoReward=new ItemReward(file.newID(),automatic,new ItemStack(Items.GOLD_INGOT));var config=new CompoundTag();
        autoReward.writeData(config,server.registryAccess());
        config.putBoolean("team_reward",false);config.putString("auto",RewardAutoClaim.NO_TOAST.id);
        autoReward.readData(config,server.registryAccess());autoReward.onCreated();file.clearCachedData();file.clearCachedProgress();
        service.bind(source,autoTask.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
        trade(api,actor,shop,"automatic-reward");
        check(team.getProgress(autoTask)==1 && actor.getInventory().countItem(Items.GOLD_INGOT)==1 && team.isRewardClaimed(actor.getUUID(),autoReward)
                && service.entries(source).stream().filter(e->e.binding().taskId().equals(autoTask.getCodeString())).allMatch(e->e.state()==State.APPLIED),
                "actual_automatic_claim_accepts_confirmed_commerce_progress_before_applied_acknowledgment");

        var repeat=new Quest(file.newID(),chapter);config=new CompoundTag();config.putBoolean("can_repeat",true);config.putInt("repeat_cooldown",0);
        repeat.readData(config,server.registryAccess());repeat.onCreated();var repeatTask=new CustomTask(file.newID(),repeat);repeatTask.onCreated();
        var repeatReward=new ItemReward(file.newID(),repeat,new ItemStack(Items.IRON_INGOT));repeatReward.onCreated();file.clearCachedData();file.clearCachedProgress();
        service.bind(source,repeatTask.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
        int initialCycle=team.getCompletionCount(repeat);
        for(int cycle=1;cycle<=2;cycle++) {
            trade(api,actor,shop,"repeat-reward-"+cycle);team.claimReward(actor,repeatReward,false);
            check(team.getCompletionCount(repeat)==initialCycle+cycle && team.getProgress(repeatTask)==0 && actor.getInventory().countItem(Items.IRON_INGOT)==cycle,
                    "native_repeat_cycle_"+cycle+"_validates_original_receipts_before_reset_and_issues_one_reward");
        }

        var unowned=new Quest(file.newID(),chapter);unowned.onCreated();var unownedTask=new CustomTask(file.newID(),unowned);unownedTask.onCreated();
        var unownedReward=new ItemReward(file.newID(),unowned,new ItemStack(Items.AMETHYST_SHARD));unownedReward.onCreated();file.clearCachedData();file.clearCachedProgress();
        service.bind(source,unownedTask.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
        team.setProgress(unownedTask,1);
        boolean rejected=false;try{team.claimReward(actor,unownedReward,false);}catch(IllegalStateException expected){
            if(!expected.getMessage().contains("unowned or unreconciled"))throw expected;rejected=true;
        }
        check(rejected && !team.isRewardClaimed(actor.getUUID(),unownedReward) && actor.getInventory().countItem(Items.AMETHYST_SHARD)==0,
                "native_progress_without_retained_commerce_credit_cannot_issue_a_reward");
        team.setProgress(unownedTask,0);trade(api,actor,shop,"repaired-unowned-progress");team.claimReward(actor,unownedReward,false);
        check(actor.getInventory().countItem(Items.AMETHYST_SHARD)==1 && team.isRewardClaimed(actor.getUUID(),unownedReward)
                && economy.balance(actor.getUUID(),currency.id())==balance-40,
                "restoring_consistent_progress_allows_a_new_native_trade_and_exact_reward");
        return passed;
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("citizens").info("[FINANCIALAUDIT] PASS {}",name);}
}
