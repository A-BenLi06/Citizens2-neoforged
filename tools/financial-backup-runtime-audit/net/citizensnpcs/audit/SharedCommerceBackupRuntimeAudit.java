package net.citizensnpcs.audit;

import static net.citizensnpcs.audit.FinancialBackupRuntimeAudit.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import net.citizensnpcs.util.CommerceQuestTasks;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.economy.api.*;

/** Two actual party members; retain the online member's settlement while restoring away the offline member's. */
@EventBusSubscriber(modid="citizens")
public final class SharedCommerceBackupRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static boolean ran;
    private static int passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!"shared".equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_SCOPE")) || ran || event.getServer().getTickCount()<20)return;
        ran=true;
        var server=event.getServer();ServerPlayer actor=null,member=null;
        io.netty.channel.embedded.EmbeddedChannel actorChannel=null,memberChannel=null;
        String phase=System.getenv("CITIZENS_FINANCIAL_AUDIT_PHASE");
        try {
            Path statePath=server.getServerDirectory().resolve("financial-state.json");
            var state="seed".equals(phase)?new JsonObject():JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            UUID actorId="seed".equals(phase)?UUID.randomUUID():UUID.fromString(state.get("actor").getAsString());
            UUID memberId="seed".equals(phase)?UUID.randomUUID():UUID.fromString(state.get("offlineActor").getAsString());
            var file=ServerQuestFile.getInstance().orElseThrow();
            if(!"seed".equals(phase)) {
                ((CustomTask)file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16))).setMaxProgress(3);
                file.clearCachedData();file.clearCachedProgress();
            }
            actor=new ServerPlayer(server,server.overworld(),new GameProfile(actorId,"SharedOwner"),ClientInformation.createDefault());
            actor.setPos(1,-60,1);actorChannel=connect(actor);
            var source=actor.createCommandSourceStack().withPermission(2);var service=CommerceQuestTasks.current(server);
            var economy=YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();
            var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;var currency=economy.defaultCurrency();
            if("seed".equals(phase)) {
                var party=(dev.ftb.mods.ftbteams.data.PartyTeam)FTBTeamsAPI.api().getManager().createPartyTeam(actor,"SharedAudit"+actorId,"fixture",dev.ftb.mods.ftblibrary.icon.Color4I.rgb(0x335577));
                member=new ServerPlayer(server,server.overworld(),new GameProfile(memberId,"SharedMember"),ClientInformation.createDefault());
                member.setPos(1,-60,1);memberChannel=connect(member);
                party.invite(actor,List.of(member.getGameProfile()));party.join(member);
                var team=file.getTeamData(actor).orElseThrow();
                check(file.getTeamData(member).orElseThrow()==team,"native_invite_and_join_share_the_same_authoritative_task_team");
                for(var player:List.of(actor,member)) {
                    economy.ensurePlayerAccount(player.getUUID(),player.getGameProfile().getName());
                    economy.credit(player.getUUID(),currency.id(),1000,"shared-seed:"+player.getUUID(),"fixture");
                    player.getInventory().clearContent();
                }
                String shop="shared-bound-"+actorId,unrelated="shared-unbound-"+actorId;
                seed(runtime.ledger().databasePath(),shop,currency.id());seed(runtime.ledger().databasePath(),unrelated,currency.id());
                var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
                var quest=new Quest(file.newID(),chapter);quest.onCreated();var task=new CustomTask(file.newID(),quest);task.onCreated();task.setMaxProgress(3);
                file.clearCachedData();file.clearCachedProgress();
                service.bind(source,task.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
                var onlineTrade=trade(api,actor,shop,"online-first");
                check(team.getProgress(task)==1 && service.entries(source).size()==1,"online_member_has_a_valid_applied_settlement_before_the_selected_backup");
                snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-before.db"));
                var offlineTrade=trade(api,member,shop,"offline-second");
                check(team.getProgress(task)==2 && service.entries(source).stream().allMatch(e->e.state()==State.APPLIED),"two_native_trades_share_exact_progress_and_distinct_applied_receipts");
                snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-after.db"));
                server.getPlayerList().remove(member);memberChannel.finishAndReleaseAll();memberChannel=null;member=null;
                check(server.getPlayerList().getPlayer(memberId)==null && api.inspectOperation(memberId,offlineTrade.receipt().operationId()).outcome().orElseThrow().state()==CommerceOutcome.State.COMMITTED,
                        "public_inspection_can_verify_the_actually_offline_members_original_settlement");
                state.addProperty("run",System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));state.addProperty("actor",actorId.toString());state.addProperty("offlineActor",memberId.toString());
                state.addProperty("task",task.getCodeString());state.addProperty("team",team.getTeamId().toString());state.addProperty("boundShop",shop);state.addProperty("unrelatedShop",unrelated);
                state.addProperty("onlineOperation",onlineTrade.receipt().operationId().toString());state.addProperty("operation",offlineTrade.receipt().operationId().toString());
                state.addProperty("transaction",offlineTrade.receipt().transactionId().toString());state.addProperty("currency",currency.id());
                var position=api.journalPosition(actorId);state.addProperty("provider",position.journalId().toString());state.addProperty("sequence",position.sequence());
                Files.writeString(statePath,state.toString());
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)FTBTeamsAPI.api().getManager()).saveNow();team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
            } else {
                var team=file.getTeamData(actor).orElseThrow();var task=file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));
                UUID operation=UUID.fromString(state.get("operation").getAsString());
                var originals=service.entries(source);
                check(state.get("run").getAsString().equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN")) && team.getTeamId().toString().equals(state.get("team").getAsString())
                        && server.getPlayerList().getPlayer(memberId)==null,"recovery_uses_the_original_party_with_only_the_unaffected_member_online");
                check(team.getProgress(task)==2 && originals.size()==2 && originals.stream().allMatch(e->e.state()==State.APPLIED) && actor.getInventory().countItem(Items.PAPER)==1,
                        "native_task_consumer_and_online_player_saves_retain_both_original_contributions");
                check(api.findOperation(actorId,UUID.fromString(state.get("onlineOperation").getAsString())).isPresent(),"online_members_original_settlement_remains_valid_in_every_selected_backup");
                if("consistent".equals(phase)) {
                    check(api.inspectOperation(memberId,operation).outcome().orElseThrow().receipt().transactionId().toString().equals(state.get("transaction").getAsString())
                            && service.reconcile(source)==0 && team.getProgress(task)==2,"consistent_backup_reconciles_both_members_without_requiring_the_offline_session");
                    var result=trade(api,actor,state.get("boundShop").getAsString(),"consistent-next");
                    check(result.state()==CommerceOutcome.State.COMMITTED && team.getProgress(task)==3 && service.entries(source).size()==3
                            && economy.balance(actorId,currency.id())==980 && actor.getInventory().countItem(Items.PAPER)==2,
                            "consistent_offline_member_receipt_allows_exact_new_shared_task_admission");
                } else {
                    int catchup="higher".equals(phase)?2:"caughtup".equals(phase)?1:0;
                    for(int i=0;i<catchup;i++)trade(api,actor,state.get("unrelatedShop").getAsString(),"shared-catchup-"+i);
                    var position=api.journalPosition(actorId);long old=state.get("sequence").getAsLong();
                    check(position.journalId().toString().equals(state.get("provider").getAsString()) &&
                            (catchup==0?position.sequence()<old:catchup==1?position.sequence()==old:position.sequence()>old),"selected_financial_backup_has_the_expected_global_sequence_and_original_identity");
                    check(api.inspectOperation(memberId,operation).outcome().isEmpty(),"selected_backup_is_missing_only_the_offline_members_original_settlement");
                    long balance=economy.balance(actorId,currency.id());int items=actor.getInventory().countItem(Items.PAPER);
                    boolean rejected=false;
                    try{service.reconcile(source);}catch(IllegalStateException expected){
                        if(!expected.getMessage().contains("regressed")&&!expected.getMessage().contains("disappeared"))throw expected;
                        LOG.info("[FINANCIALAUDIT] expected shared rejection: {}",expected.getMessage());rejected=true;
                    }
                    check(rejected,"missing_offline_members_settlement_blocks_online_member_reconciliation");
                    check(service.entries(source).equals(originals) && team.getProgress(task)==2 && economy.balance(actorId,currency.id())==balance
                            && actor.getInventory().countItem(Items.PAPER)==items,"shared_reconciliation_rejection_preserves_both_originals_and_native_state");
                    var result=trade(api,actor,state.get("boundShop").getAsString(),"after-shared-rejection");
                    check(result.state()==CommerceOutcome.State.COMMITTED && economy.balance(actorId,currency.id())==balance-10 && actor.getInventory().countItem(Items.PAPER)==items+1,
                            "provider_still_owns_native_trades_when_shared_consumer_evidence_is_inconsistent");
                    check(service.entries(source).equals(originals) && team.getProgress(task)==2,"missing_offline_settlement_prevents_new_shared_attribution_and_progress");
                }
                check(runtime.ledger().audit().valid(),"selected_shared_financial_database_remains_natively_balanced");
            }
            LOG.info("[FINANCIALAUDIT] COMPLETE {} {} checks run={}",passed,phase,System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));
        }catch(Throwable failure){LOG.error("[FINANCIALAUDIT] FAILED shared {}",phase,failure);}
        finally {
            if(member!=null&&member.connection!=null)server.getPlayerList().remove(member);if(memberChannel!=null)memberChannel.finishAndReleaseAll();
            if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(actorChannel!=null)actorChannel.finishAndReleaseAll();server.halt(false);
        }
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[FINANCIALAUDIT] PASS {}",name);}
}
