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

/** Actual native prerequisite/alternative/minimum/started/task/chapter reward claims across financial restores. */
@EventBusSubscriber(modid="citizens")
public final class CommerceRewardGraphRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static boolean ran;
    private static int passed;
    private record Root(Quest quest, CustomTask task, ItemReward reward, String label, boolean alternative) { }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!"rewardgraph".equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_SCOPE"))||ran||event.getServer().getTickCount()<20)return;
        ran=true;var server=event.getServer();String phase=System.getenv("CITIZENS_FINANCIAL_AUDIT_PHASE");
        ServerPlayer actor=null;io.netty.channel.embedded.EmbeddedChannel channel=null;
        try {
            var statePath=server.getServerDirectory().resolve("financial-state.json");
            var state="seed".equals(phase)?new JsonObject():JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            UUID owner="seed".equals(phase)?UUID.randomUUID():UUID.fromString(state.get("actor").getAsString());
            var file=ServerQuestFile.getInstance().orElseThrow();var service=CommerceQuestTasks.current(server);var console=server.createCommandSourceStack();
            var economy=YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();
            var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;var currency=economy.defaultCurrency();var monetary=FtbMonetaryRewards.current(server);
            if(!"seed".equals(phase)) {
                ((CustomTask)file.getTask(Long.parseUnsignedLong(state.get("partialTask").getAsString(),16))).setMaxProgress(2);
                file.clearCachedData();file.clearCachedProgress();
            }
            actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"GraphBuyer"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
            var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
            if("seed".equals(phase)) {
                economy.ensurePlayerAccount(owner,actor.getGameProfile().getName());economy.credit(owner,currency.id(),1000,"graph-seed:"+owner,"fixture");actor.getInventory().clearContent();
                String shop="graph-bound-"+owner,unrelated="graph-unrelated-"+owner;
                seed(runtime.ledger().databasePath(),shop,currency.id());seed(runtime.ledger().databasePath(),unrelated,currency.id());
                var sourceChapter=chapter(file);var otherChapter=chapter(file);var optionalChapter=chapter(file);
                var commerce=task(file,sourceChapter,1,false,false);var partial=task(file,otherChapter,2,false,false);
                var optional=task(file,optionalChapter,1,true,false);var valid=task(file,optionalChapter,1,false,false);
                var sequence=task(file,otherChapter,1,false,true);var tail=new CustomTask(file.newID(),sequence.getQuest());tail.onCreated();
                var mixedChapter=chapter(file);var mixed=task(file,mixedChapter,1,false,false);
                var independent=new CustomTask(file.newID(),mixed.getQuest());independent.onCreated();
                var optionalTask=task(file,mixedChapter,1,false,false);var optionalConfig=new CompoundTag();optionalConfig.putBoolean("optional_task",true);
                optionalTask.readData(optionalConfig,server.registryAccess());
                var requiredTask=new CustomTask(file.newID(),optionalTask.getQuest());requiredTask.onCreated();
                var optionalReward=new ItemReward(file.newID(),optionalTask.getQuest(),new ItemStack(Items.DIAMOND));optionalReward.onCreated();
                var rootChapter=chapter(file);var roots=new ArrayList<Root>();
                var required=root(file,rootChapter,"required",false,List.of(commerce.getQuest()),DependencyRequirement.ALL_COMPLETED,0);roots.add(required);
                var command=new CommandReward(file.newID(),required.quest());var config=new CompoundTag();
                config.putString("command","give GraphBuyer minecraft:emerald 1");config.putInt("permission_level",2);config.putBoolean("silent",true);command.readData(config,server.registryAccess());command.onCreated();
                var money=new CustomReward(file.newID(),required.quest());config=new CompoundTag();config.putBoolean("team_reward",false);money.readData(config,server.registryAccess());money.onCreated();
                var middle=root(file,rootChapter,"middle",false,List.of(commerce.getQuest()),DependencyRequirement.ALL_COMPLETED,0);
                roots.add(root(file,rootChapter,"transitive",false,List.of(middle.quest()),DependencyRequirement.ALL_COMPLETED,0));
                roots.add(root(file,rootChapter,"task",false,List.of(commerce),DependencyRequirement.ALL_COMPLETED,0));
                roots.add(root(file,rootChapter,"chapter",false,List.of(sourceChapter),DependencyRequirement.ALL_COMPLETED,0));
                roots.add(root(file,rootChapter,"started_quest",false,List.of(partial.getQuest()),DependencyRequirement.ALL_STARTED,0));
                roots.add(root(file,rootChapter,"started_task",false,List.of(partial),DependencyRequirement.ALL_STARTED,0));
                roots.add(root(file,rootChapter,"sequential_task",false,List.of(tail),DependencyRequirement.ALL_COMPLETED,0));
                roots.add(root(file,rootChapter,"any_complete",true,List.of(commerce.getQuest(),valid.getQuest()),DependencyRequirement.ONE_COMPLETED,0));
                roots.add(root(file,rootChapter,"minimum_one",true,List.of(commerce.getQuest(),valid.getQuest()),DependencyRequirement.ALL_COMPLETED,1));
                roots.add(root(file,rootChapter,"minimum_two",false,List.of(commerce.getQuest(),valid.getQuest()),DependencyRequirement.ALL_COMPLETED,2));
                roots.add(root(file,rootChapter,"any_started",true,List.of(partial.getQuest(),valid.getQuest()),DependencyRequirement.ONE_STARTED,0));
                roots.add(root(file,rootChapter,"minimum_started_two",false,List.of(partial.getQuest(),valid.getQuest()),DependencyRequirement.ONE_STARTED,2));
                roots.add(root(file,rootChapter,"optional_chapter",true,List.of(optionalChapter),DependencyRequirement.ALL_COMPLETED,0));
                roots.add(root(file,rootChapter,"started_chapter",false,List.of(otherChapter),DependencyRequirement.ALL_STARTED,0));
                roots.add(root(file,rootChapter,"optional_started_chapter",true,List.of(optionalChapter),DependencyRequirement.ALL_STARTED,0));
                roots.add(root(file,rootChapter,"mixed_started_quest",true,List.of(mixed.getQuest()),DependencyRequirement.ALL_STARTED,0));
                roots.add(root(file,rootChapter,"independent_sibling_task",true,List.of(independent),DependencyRequirement.ALL_COMPLETED,0));
                roots.add(root(file,rootChapter,"optional_task_dependency",true,List.of(optionalTask.getQuest()),DependencyRequirement.ALL_COMPLETED,0));
                roots.add(new Root(optionalTask.getQuest(),requiredTask,optionalReward,"optional_task_direct",true));
                roots.add(root(file,rootChapter,"flexible_complete",true,List.of(commerce.getQuest()),DependencyRequirement.ALL_COMPLETED,0,true));
                roots.add(root(file,rootChapter,"flexible_started",true,List.of(partial),DependencyRequirement.ALL_STARTED,0,true));
                file.clearCachedData();file.clearCachedProgress();team.setProgress(valid,1);
                for(var owned:List.of(commerce,partial,optional,sequence,mixed,optionalTask))service.bind(source,owned.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
                monetary.bind(source,money.getCodeString(),currency.id(),BigDecimal.valueOf(50,currency.scale()));
                snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-before.db"));
                var outcome=trade(api,actor,shop,"graph-source");team.setProgress(tail,1);team.setProgress(middle.task(),1);team.setProgress(independent,1);team.setProgress(requiredTask,1);
                for(var root:roots)team.setProgress(root.task(),1);
                check(team.getProgress(commerce)==1 && team.getProgress(partial)==1 && !team.isCompleted(partial) && team.isStarted(partial)
                        && service.entries(console).size()==6 && service.entries(console).stream().allMatch(e->e.state()==State.APPLIED),"one_actual_settlement_backs_complete_partial_optional_mixed_and_sequential_native_tasks");
                for(var root:roots)check(team.getClaimType(owner,root.reward()).name().equals("CAN_CLAIM"),"native_graph_reward_is_claimable_for_"+root.label());
                check(roots.stream().filter(r->!r.label().equals("optional_task_direct")).noneMatch(r->service.bindings(console).stream().anyMatch(b->b.questId().equals(r.quest().getCodeString()))),
                        "graph_rewards_have_one_explicit_optional_task_direct_control");
                var definitions=new JsonArray();for(var root:roots){var record=new JsonObject();record.addProperty("quest",root.quest().getCodeString());record.addProperty("reward",root.reward().getCodeString());record.addProperty("label",root.label());record.addProperty("alternative",root.alternative());definitions.add(record);}state.add("roots",definitions);
                var position=api.journalPosition(owner);
                state.addProperty("run",System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));state.addProperty("actor",owner.toString());state.addProperty("team",team.getTeamId().toString());state.addProperty("scope","rewardgraph");
                state.addProperty("partialTask",partial.getCodeString());state.addProperty("command",command.getCodeString());state.addProperty("money",money.getCodeString());
                state.addProperty("operation",outcome.receipt().operationId().toString());state.addProperty("transaction",outcome.receipt().transactionId().toString());state.addProperty("currency",currency.id());state.addProperty("unrelatedShop",unrelated);
                state.addProperty("provider",position.journalId().toString());state.addProperty("sequence",position.sequence());
                Files.writeString(statePath,state.toString());snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-after.db"));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
                Files.copy(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("ftbquests").resolve(team.getTeamId()+".snbt"),server.getServerDirectory().resolve("financial-progress-seed.snbt"));
            } else {
                check(state.get("run").getAsString().equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN")) && team.getTeamId().toString().equals(state.get("team").getAsString()),"graph_recovery_uses_original_native_team_and_fixture");
                var originals=service.entries(console);check(originals.size()==6 && originals.stream().allMatch(e->e.state()==State.APPLIED),"newer_consumer_journal_retains_all_six_original_task_attributions");
                int catchup="higher".equals(phase)?2:"caughtup".equals(phase)?1:0;for(int i=0;i<catchup;i++)trade(api,actor,state.get("unrelatedShop").getAsString(),"graph-catchup-"+i);
                var inspection=api.inspectOperation(owner,UUID.fromString(state.get("operation").getAsString()));long old=state.get("sequence").getAsLong();boolean consistent="consistent".equals(phase);
                check(inspection.position().journalId().toString().equals(state.get("provider").getAsString()) && (consistent?inspection.position().sequence()==old:catchup==0?inspection.position().sequence()<old:catchup==1?inspection.position().sequence()==old:inspection.position().sequence()>old),"graph_backup_has_expected_original_financial_identity_and_sequence");
                check(consistent?inspection.outcome().orElseThrow().receipt().transactionId().toString().equals(state.get("transaction").getAsString()):inspection.outcome().isEmpty(),"selected_graph_backup_has_expected_original_settlement");
                long balance=economy.balance(owner,currency.id());int permitted=0;
                for(var element:state.getAsJsonArray("roots")) {
                    var record=element.getAsJsonObject();var reward=file.getReward(Long.parseUnsignedLong(record.get("reward").getAsString(),16));
                    check(team.getClaimType(owner,reward).name().equals("CAN_CLAIM"),"seed_native_claim_marker_is_restored_for_"+record.get("label").getAsString());
                    boolean allowed=consistent||record.get("alternative").getAsBoolean();int items=actor.getInventory().countItem(Items.DIAMOND);
                    boolean rejected=false;try{team.claimReward(actor,reward,false);}catch(IllegalStateException expected){if(allowed)throw expected;rejected=true;}
                    check(allowed?!rejected&&team.isRewardClaimed(owner,reward)&&actor.getInventory().countItem(Items.DIAMOND)==items+1
                            :rejected&&!team.isRewardClaimed(owner,reward)&&actor.getInventory().countItem(Items.DIAMOND)==items,
                            (allowed?"valid_native_dependency_path_allows_":"lost_required_commerce_dependency_blocks_")+record.get("label").getAsString());
                    if(allowed){permitted++;team.claimReward(actor,reward,false);check(actor.getInventory().countItem(Items.DIAMOND)==items+1,"repeated_graph_reward_is_a_native_noop_for_"+record.get("label").getAsString());}
                }
                for(String name:List.of("command","money")) {
                    var reward=file.getReward(Long.parseUnsignedLong(state.get(name).getAsString(),16));boolean rejected=false;
                    try{team.claimReward(actor,reward,false);}catch(IllegalStateException expected){if(consistent)throw expected;rejected=true;}
                    check(consistent?team.isRewardClaimed(owner,reward):rejected&&!team.isRewardClaimed(owner,reward),"required_graph_"+name+"_claim_matches_original_financial_backing");
                }
                check(actor.getInventory().countItem(Items.DIAMOND)==permitted && actor.getInventory().countItem(Items.EMERALD)==(consistent?1:0)
                        && economy.balance(owner,currency.id())==balance+(consistent?50:0) && monetary.claims(console).size()==(consistent?1:0)
                        && service.entries(console).equals(originals),"graph_admission_preserves_original_evidence_and_exact_native_item_command_and_money_totals");
                check(runtime.ledger().audit().valid(),"graph_reward_admission_keeps_native_financial_ledger_balanced");
            }
            LOG.info("[FINANCIALAUDIT] COMPLETE {} {} checks run={}",passed,phase,System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));
        }catch(Throwable failure){LOG.error("[FINANCIALAUDIT] FAILED rewardgraph {}",phase,failure);}
        finally {if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static Chapter chapter(ServerQuestFile file){var c=new Chapter(file.newID(),file,file.getDefaultChapterGroup());c.onCreated();return c;}
    private static CustomTask task(ServerQuestFile file,Chapter chapter,int maximum,boolean optional,boolean sequential){var quest=new Quest(file.newID(),chapter);var config=new CompoundTag();config.putBoolean("optional",optional);config.putBoolean("require_sequential_tasks",sequential);quest.readData(config,file.server.registryAccess());quest.onCreated();var task=new CustomTask(file.newID(),quest);task.onCreated();task.setMaxProgress(maximum);return task;}
    private static Root root(ServerQuestFile file,Chapter chapter,String label,boolean alternative,List<? extends QuestObject> dependencies,DependencyRequirement requirement,int minimum){
        return root(file,chapter,label,alternative,dependencies,requirement,minimum,false);
    }
    private static Root root(ServerQuestFile file,Chapter chapter,String label,boolean alternative,List<? extends QuestObject> dependencies,DependencyRequirement requirement,int minimum,boolean flexible){
        var quest=new Quest(file.newID(),chapter);var config=new CompoundTag();config.putString("dependency_requirement",requirement.getId());config.putInt("min_required_dependencies",minimum);if(flexible)config.putString("progression_mode",ProgressionMode.FLEXIBLE.getId());quest.readData(config,file.server.registryAccess());quest.onCreated();for(var dependency:dependencies)quest.addDependency(dependency);
        var task=new CustomTask(file.newID(),quest);task.onCreated();var reward=new ItemReward(file.newID(),quest,new ItemStack(Items.DIAMOND));reward.onCreated();return new Root(quest,task,reward,label,alternative);
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[FINANCIALAUDIT] PASS {}",name);}
}
