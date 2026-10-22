package net.citizensnpcs.audit;

import static net.citizensnpcs.audit.FinancialBackupRuntimeAudit.*;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.util.CommerceQuestTasks;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.economy.api.*;

/** Actual stopped FTB-file restoration, without connecting either original contributor. */
@EventBusSubscriber(modid="citizens")
public final class OfflineCommerceBackupRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static boolean ran;
    private static int passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        String scope=System.getenv("CITIZENS_FINANCIAL_AUDIT_SCOPE"),phase=System.getenv("CITIZENS_FINANCIAL_AUDIT_PHASE");
        if(!("offline".equals(scope)||"newmember".equals(scope))||"seed".equals(phase)||ran||event.getServer().getTickCount()<20)return;
        ran=true;var server=event.getServer();ServerPlayer actor=null;io.netty.channel.embedded.EmbeddedChannel channel=null;
        try {
            var state=JsonParser.parseString(Files.readString(server.getServerDirectory().resolve("financial-state.json"))).getAsJsonObject();
            UUID owner=UUID.fromString(state.get("actor").getAsString()),member=UUID.fromString(state.get("offlineActor").getAsString());
            UUID operation=UUID.fromString(state.get("operation").getAsString());
            var file=ServerQuestFile.getInstance().orElseThrow();var task=(CustomTask)file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));
            task.setMaxProgress(3);file.clearCachedData();file.clearCachedProgress();
            var team=file.getNullableTeamData(UUID.fromString(state.get("team").getAsString()));
            var service=CommerceQuestTasks.current(server);var source=server.createCommandSourceStack();
            var economy=YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();
            var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;
            var originals=service.entries(source);String currency=state.get("currency").getAsString();
            check(state.get("run").getAsString().equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN")) && server.getPlayerList().getPlayerCount()==0,
                    "stopped_native_recovery_starts_with_no_connected_player_or_original_contributor");
            check(team!=null && team.getProgress(task)==1 && originals.size()==2 && originals.stream().allMatch(e->e.state()==State.APPLIED),
                    "actual_older_native_task_file_has_one_unit_while_both_immutable_applied_receipts_survive");
            check(api.inspectOperation(owner,UUID.fromString(state.get("onlineOperation").getAsString())).outcome().isPresent(),
                    "first_original_contributors_financial_receipt_is_retained_in_every_backup");
            int catchup="higher".equals(phase)?2:"caughtup".equals(phase)?1:0;
            if(catchup>0) {
                actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"SharedOwner"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
                for(int i=0;i<catchup;i++)trade(api,actor,state.get("unrelatedShop").getAsString(),"offline-catchup-"+i);
                server.getPlayerList().remove(actor);channel.finishAndReleaseAll();channel=null;actor=null;
            }
            var inspection=api.inspectOperation(member,operation);long old=state.get("sequence").getAsLong();
            check(inspection.position().journalId().toString().equals(state.get("provider").getAsString()) &&
                    ("consistent".equals(phase)?inspection.position().sequence()==old:catchup==0?inspection.position().sequence()<old:catchup==1?inspection.position().sequence()==old:inspection.position().sequence()>old),
                    "selected_backup_has_the_original_journal_identity_and_requested_high_water");
            boolean newcomer="newmember".equals(scope);
            if(newcomer) {
                var id=UUID.fromString(state.get("newActor").getAsString());
                actor=new ServerPlayer(server,server.overworld(),new GameProfile(id,"RecoveryMember"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
                check(file.getTeamData(actor).orElseThrow()==team && service.entries(source).stream().noneMatch(e->e.request().actorId().equals(id))
                        && server.getPlayerList().getPlayer(owner)==null && server.getPlayerList().getPlayer(member)==null,
                        "only_the_new_party_member_is_online_and_has_no_retained_receipts");
            } else check(server.getPlayerList().getPlayerCount()==0,"console_reconciliation_has_no_player_session_to_replay");
            long ownerBalance=economy.balance(owner,currency),memberBalance=economy.balance(member,currency);
            if(!"consistent".equals(phase)) {
                check(inspection.outcome().isEmpty(),"the_selected_backup_is_missing_the_second_original_contributors_settlement");
                boolean rejected=false;
                try {if(newcomer)service.replay(actor);else service.reconcile(source);}
                catch(IllegalStateException expected){
                    if(!expected.getMessage().contains("regressed")&&!expected.getMessage().contains("disappeared"))throw expected;
                    LOG.info("[FINANCIALAUDIT] expected offline rejection: {}",expected.getMessage());rejected=true;
                }
                check(rejected,"missing_original_settlement_is_rejected_without_replaying_an_original_actor");
                check(team.getProgress(task)==1 && service.entries(source).equals(originals) && economy.balance(owner,currency)==ownerBalance && economy.balance(member,currency)==memberBalance,
                        "rejected_offline_or_new_member_recovery_preserves_original_evidence_native_progress_and_money");
                if(newcomer) {
                    long before=economy.balance(actor.getUUID(),currency);int items=actor.getInventory().countItem(Items.PAPER);
                    var result=trade(api,actor,state.get("boundShop").getAsString(),"new-member-after-rejection");
                    check(result.state()==CommerceOutcome.State.COMMITTED && economy.balance(actor.getUUID(),currency)==before-10 && actor.getInventory().countItem(Items.PAPER)==items+1,
                            "new_member_native_trade_still_belongs_to_the_provider");
                    check(team.getProgress(task)==1 && service.entries(source).equals(originals),"new_member_cannot_admit_more_consumer_history_or_progress_over_missing_shared_evidence");
                }
            } else {
                check(inspection.outcome().orElseThrow().receipt().transactionId().toString().equals(state.get("transaction").getAsString()),
                        "matching_ledger_retains_the_exact_original_second_transaction");
                int count=newcomer?service.replay(actor):service.reconcile(source);
                check(count==0 && team.getProgress(task)==2 && service.entries(source).equals(originals),
                        "consistent_original_receipts_restore_absolute_native_progress_without_replaying_contributors");
                check(economy.balance(owner,currency)==ownerBalance && economy.balance(member,currency)==memberBalance,
                        "offline_projection_does_not_repeat_original_payments");
                if(newcomer) {
                    long before=economy.balance(actor.getUUID(),currency);int items=actor.getInventory().countItem(Items.PAPER);
                    var result=trade(api,actor,state.get("boundShop").getAsString(),"valid-new-member");
                    check(result.state()==CommerceOutcome.State.COMMITTED && team.getProgress(task)==3 && service.entries(source).size()==3
                            && economy.balance(actor.getUUID(),currency)==before-10 && actor.getInventory().countItem(Items.PAPER)==items+1,
                            "a_new_member_can_add_exactly_one_native_contribution_after_consistent_recovery");
                } else {
                    check(service.reconcile(source)==0 && team.getProgress(task)==2 && service.entries(source).equals(originals),"repeated_console_recovery_does_not_duplicate_any_original_evidence_or_progress");
                    actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"SharedOwner"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
                    actor.getInventory().clearContent();for(int slot=0;slot<actor.getInventory().items.size();slot++)actor.getInventory().setItem(slot,new ItemStack(Items.STONE,64));
                    var pending=trade(api,actor,state.get("boundShop").getAsString(),"offline-pending");
                    check(pending.state()==CommerceOutcome.State.DELIVERY_PENDING && service.entries(source).stream().anyMatch(e->e.receipt()!=null&&e.receipt().operationId().equals(pending.receipt().operationId())&&e.state()==State.WAITING)
                            && team.getProgress(task)==2,"full_native_inventory_retains_an_actual_waiting_delivery_without_task_credit");
                    actor.getInventory().setItem(0,ItemStack.EMPTY);check(api.claimDeliveries(owner)==1 && actor.getInventory().countItem(Items.PAPER)==1,
                            "native_pending_delivery_finishes_once_before_the_original_actor_disconnects");
                    server.getPlayerList().remove(actor);channel.finishAndReleaseAll();channel=null;actor=null;
                    long paid=economy.balance(owner,currency);var position=api.inspectOperation(owner,pending.receipt().operationId()).position();
                    check(server.getPlayerList().getPlayerCount()==0 && service.reconcile(source)==0 && team.getProgress(task)==3
                            && service.entries(source).stream().allMatch(e->e.state()==State.APPLIED),"console_recovery_confirms_and_projects_the_original_waiting_delivery_with_every_contributor_offline");
                    check(service.reconcile(source)==0 && economy.balance(owner,currency)==paid && api.inspectOperation(owner,pending.receipt().operationId()).position().equals(position),
                            "settled_offline_delivery_reconciliation_does_not_charge_deliver_or_record_another_trade");
                }
            }
            check(runtime.ledger().audit().valid(),"offline_recovery_financial_database_remains_natively_balanced");
            LOG.info("[FINANCIALAUDIT] COMPLETE {} {} checks run={}",passed,phase,System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));
        }catch(Throwable failure){LOG.error("[FINANCIALAUDIT] FAILED {} {}",scope,phase,failure);}
        finally {if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[FINANCIALAUDIT] PASS {}",name);}
}
