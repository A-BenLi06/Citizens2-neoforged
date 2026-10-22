package net.citizensnpcs.audit;

import static net.citizensnpcs.audit.FinancialBackupRuntimeAudit.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.reward.*;
import net.citizensnpcs.util.*;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.economy.api.*;

/** Actual native item/command/monetary claims against selected stopped financial backups. */
@EventBusSubscriber(modid="citizens")
public final class CommerceRewardBackupRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static boolean ran;
    private static int passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        String scope=System.getenv("CITIZENS_FINANCIAL_AUDIT_SCOPE");boolean shared="rewardshared".equals(scope);
        if(!("reward".equals(scope)||shared)||ran||event.getServer().getTickCount()<20)return;
        ran=true;var server=event.getServer();String phase=System.getenv("CITIZENS_FINANCIAL_AUDIT_PHASE");
        ServerPlayer actor=null,member=null;io.netty.channel.embedded.EmbeddedChannel channel=null,memberChannel=null;
        try {
            Path statePath=server.getServerDirectory().resolve("financial-state.json");
            var state="seed".equals(phase)?new JsonObject():JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            UUID owner="seed".equals(phase)?UUID.randomUUID():UUID.fromString(state.get("actor").getAsString());
            var file=ServerQuestFile.getInstance().orElseThrow();var service=CommerceQuestTasks.current(server);var console=server.createCommandSourceStack();
            var economy=YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();
            var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;var currency=economy.defaultCurrency();var rewards=FtbMonetaryRewards.current(server);
            if(shared&&!"seed".equals(phase))((CustomTask)file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16))).setMaxProgress(2);
            actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"RewardBuyer"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
            dev.ftb.mods.ftbteams.data.PartyTeam party=null;
            if(shared&&"seed".equals(phase))party=(dev.ftb.mods.ftbteams.data.PartyTeam)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()
                    .createPartyTeam(actor,"RewardAudit"+owner,"fixture",dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x335577));
            var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
            if("seed".equals(phase)) {
                economy.ensurePlayerAccount(owner,actor.getGameProfile().getName());economy.credit(owner,currency.id(),1000,"reward-seed:"+owner,"fixture");actor.getInventory().clearContent();
                if(shared) {
                    member=new ServerPlayer(server,server.overworld(),new GameProfile(UUID.randomUUID(),"RewardMember"),ClientInformation.createDefault());member.setPos(1,-60,1);memberChannel=connect(member);
                    party.invite(actor,List.of(member.getGameProfile()));party.join(member);
                    economy.ensurePlayerAccount(member.getUUID(),member.getGameProfile().getName());economy.credit(member.getUUID(),currency.id(),1000,"reward-member-seed:"+member.getUUID(),"fixture");member.getInventory().clearContent();
                    state.addProperty("offlineActor",member.getUUID().toString());
                    check(file.getTeamData(member).orElseThrow()==team,"native_reward_party_contains_both_original_contributors");
                }
                String shop="reward-bound-"+owner,unrelated="reward-unrelated-"+owner;
                seed(runtime.ledger().databasePath(),shop,currency.id());seed(runtime.ledger().databasePath(),unrelated,currency.id());
                var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
                var quest=new Quest(file.newID(),chapter);quest.onCreated();var task=new CustomTask(file.newID(),quest);task.onCreated();if(shared)task.setMaxProgress(2);
                var item=new ItemReward(file.newID(),quest,new ItemStack(Items.DIAMOND));item.onCreated();
                var command=new CommandReward(file.newID(),quest);var config=new CompoundTag();
                config.putString("command","give RewardBuyer minecraft:emerald 1");config.putInt("permission_level",2);config.putBoolean("silent",true);
                command.readData(config,server.registryAccess());command.onCreated();
                var money=new CustomReward(file.newID(),quest);config=new CompoundTag();config.putBoolean("team_reward",false);money.readData(config,server.registryAccess());money.onCreated();
                file.clearCachedData();file.clearCachedProgress();
                service.bind(source,task.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
                rewards.bind(source,money.getCodeString(),currency.id(),BigDecimal.valueOf(50,currency.scale()));
                if(shared)state.addProperty("onlineOperation",trade(api,actor,shop,"reward-valid-first").receipt().operationId().toString());
                snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-before.db"));
                var trade=trade(api,shared?member:actor,shop,"reward-source");
                state.addProperty("operationOwner",(shared?member:actor).getUUID().toString());
                if(member!=null){server.getPlayerList().remove(member);memberChannel.finishAndReleaseAll();memberChannel=null;member=null;}
                check(team.getProgress(task)==task.getMaxProgress() && team.isCompleted(quest) && service.entries(console).stream().allMatch(e->e.state()==State.APPLIED),"actual_commerce_settlement_completes_the_native_reward_quest");
                check(List.of(item,command,money).stream().allMatch(r->team.getClaimType(owner,r).name().equals("CAN_CLAIM"))
                        && actor.getInventory().countItem(Items.DIAMOND)==0 && actor.getInventory().countItem(Items.EMERALD)==0 && rewards.claims(console).isEmpty(),"all_three_native_rewards_are_claimable_but_unclaimed_at_the_financial_snapshot");
                var position=api.journalPosition(owner);
                state.addProperty("run",System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));state.addProperty("actor",owner.toString());state.addProperty("team",team.getTeamId().toString());
                state.addProperty("task",task.getCodeString());state.addProperty("quest",quest.getCodeString());state.addProperty("item",item.getCodeString());state.addProperty("command",command.getCodeString());state.addProperty("money",money.getCodeString());
                state.addProperty("operation",trade.receipt().operationId().toString());state.addProperty("transaction",trade.receipt().transactionId().toString());state.addProperty("currency",currency.id());state.addProperty("unrelatedShop",unrelated);
                state.addProperty("provider",position.journalId().toString());state.addProperty("sequence",position.sequence());
                state.addProperty("scope",scope);
                Files.writeString(statePath,state.toString());snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-after.db"));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
            } else {
                var task=file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));var quest=task.getQuest();
                var item=file.getReward(Long.parseUnsignedLong(state.get("item").getAsString(),16));var command=file.getReward(Long.parseUnsignedLong(state.get("command").getAsString(),16));var money=file.getReward(Long.parseUnsignedLong(state.get("money").getAsString(),16));
                var originals=service.entries(console);UUID operation=UUID.fromString(state.get("operation").getAsString());
                check(state.get("run").getAsString().equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN")) && team.getTeamId().toString().equals(state.get("team").getAsString()),"reward_recovery_uses_the_original_native_team_and_fixture");
                check(team.getProgress(task)==task.getMaxProgress() && team.isCompleted(quest) && originals.size()==(shared?2:1) && originals.stream().allMatch(e->e.state()==State.APPLIED)
                        && List.of(item,command,money).stream().allMatch(r->team.getClaimType(owner,r).name().equals("CAN_CLAIM")),"newer_native_task_save_still_exposes_all_original_unclaimed_rewards");
                if(shared)check(server.getPlayerList().getPlayer(UUID.fromString(state.get("offlineActor").getAsString()))==null
                        && api.findOperation(owner,UUID.fromString(state.get("onlineOperation").getAsString())).isPresent(),"claimant_retains_a_valid_settlement_while_the_other_original_contributor_is_offline");
                int catchup="higher".equals(phase)?2:"caughtup".equals(phase)?1:0;
                for(int i=0;i<catchup;i++)trade(api,actor,state.get("unrelatedShop").getAsString(),"reward-catchup-"+i);
                var inspection=api.inspectOperation(UUID.fromString(state.get("operationOwner").getAsString()),operation);long old=state.get("sequence").getAsLong();
                check(inspection.position().journalId().toString().equals(state.get("provider").getAsString()) &&
                        ("consistent".equals(phase)?inspection.position().sequence()==old:catchup==0?inspection.position().sequence()<old:catchup==1?inspection.position().sequence()==old:inspection.position().sequence()>old),"reward_backup_has_the_expected_original_financial_identity_and_sequence");
                long balance=economy.balance(owner,currency.id());
                if(!"consistent".equals(phase)) {
                    check(inspection.outcome().isEmpty(),"older_financial_backup_has_lost_the_original_reward_task_settlement");
                    for(var reward:List.of(item,command,money)) {
                        boolean rejected=false;try{team.claimReward(actor,reward,false);}catch(IllegalStateException expected){rejected=true;}
                        check(rejected && team.getClaimType(owner,reward).name().equals("CAN_CLAIM"),"missing_financial_backing_blocks_native_"+reward.getType().getTypeId()+"_before_claim_marking");
                    }
                    check(actor.getInventory().countItem(Items.DIAMOND)==0 && actor.getInventory().countItem(Items.EMERALD)==0
                            && rewards.claims(console).isEmpty() && economy.balance(owner,currency.id())==balance && service.entries(console).equals(originals)
                            && team.getProgress(task)==task.getMaxProgress(),"rejected_native_claims_preserve_money_items_command_effects_claim_journal_and_original_commerce_evidence");
                } else {
                    check(inspection.outcome().orElseThrow().receipt().transactionId().toString().equals(state.get("transaction").getAsString()),"matching_reward_backup_retains_the_original_financial_transaction");
                    for(var reward:List.of(item,command,money))team.claimReward(actor,reward,false);
                    check(actor.getInventory().countItem(Items.DIAMOND)==1 && actor.getInventory().countItem(Items.EMERALD)==1
                            && economy.balance(owner,currency.id())==balance+50 && rewards.claims(console).size()==1 && rewards.claims(console).getFirst().state()==QuestRewardJournal.State.PAID,
                            "matching_financial_backing_allows_actual_native_item_command_and_monetary_outcomes");
                    for(var reward:List.of(item,command,money))team.claimReward(actor,reward,false);
                    check(actor.getInventory().countItem(Items.DIAMOND)==1 && actor.getInventory().countItem(Items.EMERALD)==1
                            && economy.balance(owner,currency.id())==balance+50 && rewards.claims(console).size()==1 && service.entries(console).equals(originals),"repeated_native_claims_do_not_issue_items_execute_commands_or_pay_twice");
                    passed+=CommerceRewardAdmissionControls.run(actor,file,economy,state.get("unrelatedShop").getAsString(),List.of(item,command,money));
                }
                check(runtime.ledger().audit().valid(),"reward_admission_keeps_the_native_financial_ledger_balanced");
            }
            LOG.info("[FINANCIALAUDIT] COMPLETE {} {} checks run={}",passed,phase,System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));
        }catch(Throwable failure){LOG.error("[FINANCIALAUDIT] FAILED reward {}",phase,failure);}
        finally {if(member!=null&&member.connection!=null)server.getPlayerList().remove(member);if(memberChannel!=null)memberChannel.finishAndReleaseAll();if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[FINANCIALAUDIT] PASS {}",name);}
}
