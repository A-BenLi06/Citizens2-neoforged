package net.citizensnpcs.audit;

import static net.citizensnpcs.audit.FinancialBackupRuntimeAudit.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.util.CommerceQuestTasks;
import net.citizensnpcs.util.FtbQuestTasks;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.economy.api.*;
import net.yuuniverse.economy.core.*;

/** Native dependency recovery with deterministic retained receipt order opposite to prerequisite order. */
@EventBusSubscriber(modid="citizens")
public final class DependentCommerceRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static boolean ran;
    private static int passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        String scope=System.getenv("CITIZENS_FINANCIAL_AUDIT_SCOPE");
        if(!List.of("dependency","sequential").contains(scope)||ran||event.getServer().getTickCount()<20)return;
        boolean sequential="sequential".equals(scope);
        ran=true;var server=event.getServer();String phase=System.getenv("CITIZENS_FINANCIAL_AUDIT_PHASE");
        ServerPlayer actor=null;io.netty.channel.embedded.EmbeddedChannel channel=null;
        try {
            Path statePath=server.getServerDirectory().resolve("financial-state.json");
            var state="seed".equals(phase)?new JsonObject():JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            UUID owner="seed".equals(phase)?UUID.randomUUID():UUID.fromString(state.get("actor").getAsString());
            var file=ServerQuestFile.getInstance().orElseThrow();var service=CommerceQuestTasks.current(server);var console=server.createCommandSourceStack();
            var economy=YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();
            var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;var currency=economy.defaultCurrency();
            if("seed".equals(phase)) {
                actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"DependencyOwner"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
                var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();
                economy.ensurePlayerAccount(owner,actor.getGameProfile().getName());economy.credit(owner,currency.id(),1000,"dependency-seed:"+owner,"fixture");actor.getInventory().clearContent();
                String prerequisiteShop="prerequisite-"+owner,dependentShop="dependent-"+owner,unrelatedShop="unrelated-"+owner;
                for(String shop:List.of(prerequisiteShop,dependentShop,unrelatedShop))seed(runtime.ledger().databasePath(),shop,currency.id());
                var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
                var prerequisiteQuest=new Quest(file.newID(),chapter);
                if(sequential) {
                    var config=new net.minecraft.nbt.CompoundTag();config.putBoolean("require_sequential_tasks",true);
                    prerequisiteQuest.readData(config,server.registryAccess());
                }
                prerequisiteQuest.onCreated();var prerequisite=new CustomTask(file.newID(),prerequisiteQuest);prerequisite.onCreated();
                Quest dependentQuest;
                if(sequential)dependentQuest=prerequisiteQuest;
                else {dependentQuest=new Quest(file.newID(),chapter);dependentQuest.onCreated();dependentQuest.addDependency(prerequisiteQuest);}
                var dependent=new CustomTask(file.newID(),dependentQuest);dependent.onCreated();file.clearCachedData();file.clearCachedProgress();
                check(!FtbQuestTasks.evidenceTarget(server,team.getTeamId(),dependent.getCodeString()).state().available()
                        && team.getProgress(prerequisite)==0 && team.getProgress(dependent)==0,"native_prerequisite_initially_blocks_the_dependent_task");
                var first=service.bind(source,prerequisite.getCodeString(),Shop.SYSTEM,prerequisiteShop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
                team.saveIfChanged();file.saveNow();
                var progress=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("ftbquests").resolve(team.getTeamId()+".snbt");
                Files.copy(progress,server.getServerDirectory().resolve("financial-progress-before.snbt"));
                var firstTrade=execute(api,actor,prerequisiteShop,key(first,owner,"f"));
                check(team.getProgress(prerequisite)==1 && FtbQuestTasks.evidenceTarget(server,team.getTeamId(),dependent.getCodeString()).state().available(),"first_actual_trade_completes_the_native_prerequisite_and_unlocks_the_dependent");
                snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-before.db"));
                var second=service.bind(source,dependent.getCodeString(),Shop.SYSTEM,dependentShop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
                var secondTrade=execute(api,actor,dependentShop,key(second,owner,"0"));
                var entries=service.entries(source);var firstEntry=entries.stream().filter(e->e.binding().equals(first)).findFirst().orElseThrow();
                var secondEntry=entries.stream().filter(e->e.binding().equals(second)).findFirst().orElseThrow();
                check(team.getProgress(dependent)==1 && entries.size()==2 && entries.stream().allMatch(e->e.state()==State.APPLIED)
                        && economy.balance(owner,currency.id())==980 && actor.getInventory().countItem(Items.PAPER)==2,"two_actual_native_trades_retain_exact_paid_delivered_and_applied_dependency_evidence");
                check(secondEntry.id().compareTo(firstEntry.id())<0,"fixture_receipt_ids_load_the_dependent_before_its_prerequisite_after_restart");
                state.addProperty("run",System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));state.addProperty("actor",owner.toString());state.addProperty("team",team.getTeamId().toString());
                state.addProperty("scope",scope);
                state.addProperty("prerequisite",prerequisite.getCodeString());state.addProperty("task",dependent.getCodeString());
                state.addProperty("onlineOperation",firstTrade.receipt().operationId().toString());state.addProperty("operation",secondTrade.receipt().operationId().toString());
                state.addProperty("transaction",secondTrade.receipt().transactionId().toString());state.addProperty("currency",currency.id());state.addProperty("unrelatedShop",unrelatedShop);
                var position=api.journalPosition(owner);state.addProperty("provider",position.journalId().toString());state.addProperty("sequence",position.sequence());
                Files.writeString(statePath,state.toString());snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-after.db"));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
            } else {
                var team=file.getNullableTeamData(UUID.fromString(state.get("team").getAsString()));
                var prerequisite=(CustomTask)file.getTask(Long.parseUnsignedLong(state.get("prerequisite").getAsString(),16));
                var dependent=(CustomTask)file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));
                var originals=service.entries(console);UUID operation=UUID.fromString(state.get("operation").getAsString());
                check(state.get("run").getAsString().equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN")) && server.getPlayerList().getPlayerCount()==0,"original_dependency_fixture_restarts_with_no_contributor_session");
                check(team.getProgress(prerequisite)==0 && team.getProgress(dependent)==0
                        && !FtbQuestTasks.evidenceTarget(server,team.getTeamId(),dependent.getCodeString()).state().available(),"actual_older_native_team_file_restores_both_tasks_and_the_prerequisite_gate");
                check(originals.size()==2 && originals.stream().allMatch(e->e.state()==State.APPLIED)
                        && originals.getFirst().binding().taskId().equals(dependent.getCodeString()),"retained_applied_receipts_are_loaded_in_dependency_reverse_order");
                int catchup="higher".equals(phase)?2:"caughtup".equals(phase)?1:0;
                if(catchup>0) {
                    actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"DependencyOwner"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
                    for(int i=0;i<catchup;i++)trade(api,actor,state.get("unrelatedShop").getAsString(),"dependency-catchup-"+i);
                    server.getPlayerList().remove(actor);channel.finishAndReleaseAll();channel=null;actor=null;
                }
                var inspection=api.inspectOperation(owner,operation);long old=state.get("sequence").getAsLong();
                check(inspection.position().journalId().toString().equals(state.get("provider").getAsString()) &&
                        ("consistent".equals(phase)?inspection.position().sequence()==old:catchup==0?inspection.position().sequence()<old:catchup==1?inspection.position().sequence()==old:inspection.position().sequence()>old),"selected_dependency_backup_has_the_expected_original_identity_and_sequence");
                long balance=economy.balance(owner,currency.id());
                if(!"consistent".equals(phase)) {
                    check(inspection.outcome().isEmpty(),"selected_older_financial_backup_lost_the_dependent_settlement");
                    boolean rejected=false;try{service.reconcile(console);}catch(IllegalStateException expected){
                        if(!expected.getMessage().contains("regressed")&&!expected.getMessage().contains("disappeared"))throw expected;rejected=true;
                    }
                    check(rejected && team.getProgress(prerequisite)==0 && team.getProgress(dependent)==0 && service.entries(console).equals(originals)
                            && economy.balance(owner,currency.id())==balance,"financial_prevalidation_rejects_the_inconsistent_chain_before_any_native_projection");
                } else {
                    check(inspection.outcome().orElseThrow().receipt().transactionId().toString().equals(state.get("transaction").getAsString()),"matching_financial_backup_retains_the_original_dependent_transaction");
                    check(service.reconcile(console)==0 && team.getProgress(prerequisite)==1 && team.getProgress(dependent)==1 && service.entries(console).equals(originals),
                            "valid_native_dependency_chain_recovers_in_one_call_despite_reverse_receipt_order");
                    check(service.reconcile(console)==0 && economy.balance(owner,currency.id())==balance,"repeated_dependency_recovery_does_not_repeat_payment_or_credit");
                    if(!sequential) {
                    team.deserializeNBT(SNBT.tryRead(server.getServerDirectory().resolve("financial-progress-before.snbt")));team.clearCachedProgress();
                    dependent.setMaxProgress(2);file.clearCachedData();file.clearCachedProgress();
                    boolean reported=false;try{service.reconcile(console);}catch(IllegalStateException expected){
                        Throwable cause=expected;while(cause!=null&&!String.valueOf(cause.getMessage()).contains("definition"))cause=cause.getCause();
                        if(cause==null)throw expected;
                        reported=expected.getMessage().contains(dependent.getCodeString()) && expected.getMessage().contains(team.getTeamId().toString());
                    }
                    check(reported && team.getProgress(prerequisite)==1 && team.getProgress(dependent)==0 && service.entries(console).equals(originals),
                            "invalid_dependent_definition_is_reported_after_valid_prerequisite_recovery_with_original_evidence_intact");
                    dependent.setMaxProgress(1);file.clearCachedData();file.clearCachedProgress();
                    check(service.reconcile(console)==0 && team.getProgress(prerequisite)==1 && team.getProgress(dependent)==1
                            && economy.balance(owner,currency.id())==balance,"repairing_the_original_definition_allows_only_the_remaining_projection");
                    }
                }
                check(runtime.ledger().audit().valid(),"dependency_recovery_financial_database_remains_natively_balanced");
            }
            LOG.info("[FINANCIALAUDIT] COMPLETE {} {} checks run={}",passed,phase,System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));
        }catch(Throwable failure){LOG.error("[FINANCIALAUDIT] FAILED dependency {}",phase,failure);}
        finally {if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static CommerceOutcome execute(CommerceApi api,ServerPlayer actor,String shop,String key) {
        var request=new CommerceRequest(CommerceShopType.SYSTEM,shop,"paper",CommerceAction.BUY_FROM_SHOP,AccountIds.player(actor.getUUID()),1,key,"audit:dependency");
        return api.execute(actor.getUUID(),api.quote(actor.getUUID(),request));
    }
    private static String key(Binding binding,UUID owner,String prefix)throws Exception {
        // Select fixture identities only; every settlement and attribution still uses the native public path.
        for(int i=0;i<100000;i++) {
            String key="dependency:"+binding.epoch()+":"+i;
            String value=binding.epoch()+"\0"+binding.journalId()+"\0"+owner+"\0request\0"+key;
            String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
            if(id.startsWith(prefix))return key;
        }
        throw new IllegalStateException("Could not select deterministic dependency fixture receipt order");
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[FINANCIALAUDIT] PASS {}",name);}
}
