package net.citizensnpcs.audit;

import static net.citizensnpcs.audit.FinancialBackupRuntimeAudit.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import net.citizensnpcs.util.*;
import net.minecraft.server.level.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.economy.api.*;

/** Actual native PAID reward retained across individually restored financial snapshots. */
@EventBusSubscriber(modid="citizens")
public final class MonetaryPaidRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static boolean ran; private static int passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(!"paid".equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_SCOPE"))||ran||event.getServer().getTickCount()<20)return;
        ran=true;var server=event.getServer();String phase=System.getenv("CITIZENS_FINANCIAL_AUDIT_PHASE");
        ServerPlayer actor=null;io.netty.channel.embedded.EmbeddedChannel channel=null;
        try {
            var statePath=server.getServerDirectory().resolve("financial-state.json");
            var state="seed".equals(phase)?new JsonObject():JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            UUID owner="seed".equals(phase)?UUID.randomUUID():UUID.fromString(state.get("actor").getAsString());
            var file=ServerQuestFile.getInstance().orElseThrow();var service=FtbMonetaryRewards.current(server);var console=server.createCommandSourceStack();
            var economy=YuuniverseEconomyApi.current().orElseThrow();var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;var currency=economy.defaultCurrency();
            actor=new ServerPlayer(server,server.overworld(),new GameProfile(owner,"PaidBuyer"),ClientInformation.createDefault());actor.setPos(1,-60,1);channel=connect(actor);
            var source=actor.createCommandSourceStack().withPermission(2);var team=file.getTeamData(actor).orElseThrow();var database=runtime.ledger().databasePath();
            if("seed".equals(phase)) {
                economy.ensurePlayerAccount(owner,actor.getGameProfile().getName());economy.credit(owner,currency.id(),1000,"paid-seed:"+owner,"fixture");
                var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();var quest=new Quest(file.newID(),chapter);quest.onCreated();
                var task=new CustomTask(file.newID(),quest);task.onCreated();var money=new CustomReward(file.newID(),quest);money.onCreated();
                var later=new CustomReward(file.newID(),quest);later.onCreated();
                var pendingQuest=new Quest(file.newID(),chapter);pendingQuest.onCreated();var pendingTask=new CustomTask(file.newID(),pendingQuest);pendingTask.onCreated();var pending=new CustomReward(file.newID(),pendingQuest);pending.onCreated();
                file.clearCachedData();file.clearCachedProgress();service.bind(source,money.getCodeString(),currency.id(),BigDecimal.valueOf(50,currency.scale()));
                service.bind(source,later.getCodeString(),currency.id(),BigDecimal.valueOf(25,currency.scale()));service.bind(source,pending.getCodeString(),currency.id(),BigDecimal.valueOf(7,currency.scale()));
                team.setProgress(task,1);team.setProgress(pendingTask,1);
                snapshot(database,server.getServerDirectory().resolve("financial-before.db"));team.claimReward(actor,money,false);
                var claim=service.claims(console).getFirst();
                check(claim.state()==QuestRewardJournal.State.PAID && team.isRewardClaimed(owner,money) && economy.balance(owner,currency.id())==1050,"actual_native_claim_records_paid_and_one_exact_provider_credit");
                check(transaction(database,claim.transactionId()),"original_paid_transaction_is_present_before_snapshot");
                YuuniverseEconomyApi.clear(economy);try{team.claimReward(actor,pending,false);}finally{YuuniverseEconomyApi.publish(economy);}
                var waiting=service.claims(console).stream().filter(c->c.state()==QuestRewardJournal.State.ACCEPTED).findFirst().orElseThrow();
                check(service.claims(console).size()==2 && team.isRewardClaimed(owner,pending) && team.getClaimType(owner,later).name().equals("CAN_CLAIM") && economy.balance(owner,currency.id())==1050,"another_native_claim_is_accepted_without_payment_and_same_quest_reward_remains_unclaimed");
                state.addProperty("run",System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));state.addProperty("actor",owner.toString());state.addProperty("team",team.getTeamId().toString());state.addProperty("scope","paid");
                state.addProperty("quest",quest.getCodeString());state.addProperty("money",money.getCodeString());state.addProperty("claim",claim.id());state.addProperty("transaction",claim.transactionId().toString());
                state.addProperty("operationKey",claim.intent().operationKey());state.addProperty("currency",currency.id());state.addProperty("transactionCount",runtime.ledger().audit().transactionCount());
                state.addProperty("pendingClaim",waiting.id());state.addProperty("pendingReward",pending.getCodeString());state.addProperty("laterReward",later.getCodeString());
                state.addProperty("provider",economy.commerceApi().orElseThrow().journalPosition(owner).journalId().toString());
                Files.writeString(statePath,state.toString());snapshot(database,server.getServerDirectory().resolve("financial-after.db"));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
            } else {
                var originals=service.claims(console);var claim=originals.stream().filter(c->c.id().equals(state.get("claim").getAsString())).findFirst().orElseThrow();
                var money=file.getReward(Long.parseUnsignedLong(state.get("money").getAsString(),16));boolean consistent="consistent".equals(phase);
                var pending=originals.stream().filter(c->c.id().equals(state.get("pendingClaim").getAsString())).findFirst().orElseThrow();
                var later=file.getReward(Long.parseUnsignedLong(state.get("laterReward").getAsString(),16));
                check(state.get("run").getAsString().equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN")) && claim.state()==QuestRewardJournal.State.PAID && claim.transactionId().toString().equals(state.get("transaction").getAsString()) && team.isRewardClaimed(owner,money),"newer_consumer_and_ftb_saves_retain_the_original_paid_native_claim");
                int catchup="higher".equals(phase)?2:"caughtup".equals(phase)?1:0;
                for(int i=0;i<catchup;i++)economy.credit(owner,currency.id(),1,"paid-unrelated:"+phase+":"+i,"fixture");
                long count=runtime.ledger().audit().transactionCount(),old=state.get("transactionCount").getAsLong();
                check((consistent?count==old+1:catchup==0?count<old:catchup==1?count==old:count>old) && transaction(database,claim.transactionId())==consistent,"selected_backup_has_expected_transaction_count_and_original_credit_presence");
                check(consistent?pending.state()==QuestRewardJournal.State.PAID && economy.balance(owner,currency.id())==1057
                        :pending.state()==QuestRewardJournal.State.ACCEPTED && economy.balance(owner,currency.id())==1000+catchup,
                        "startup_validates_paid_before_settling_any_accepted_entitlement");
                long balance=economy.balance(owner,currency.id());
                var legacy=(EconomyApi)java.lang.reflect.Proxy.newProxyInstance(EconomyApi.class.getClassLoader(),new Class<?>[]{EconomyApi.class},(proxy,method,args)->{
                    if(method.getName().equals("apiVersion"))return 4;
                    if(method.getName().equals("inspectCredit")||method.getName().equals("credit"))throw new AssertionError("unsupported_provider_must_not_inspect_or_post");
                    return method.invoke(economy,args);
                });
                YuuniverseEconomyApi.clear(economy);YuuniverseEconomyApi.publish(legacy);boolean unsupported=false;
                try{service.reconcile(console);}catch(IllegalStateException expected){unsupported=expected.getMessage().contains("revision 5");}
                finally{YuuniverseEconomyApi.clear(legacy);YuuniverseEconomyApi.publish(economy);}
                check(unsupported && service.claims(console).equals(originals) && economy.balance(owner,currency.id())==balance,"older_api_revision_holds_paid_verification_without_posting_or_changing_evidence");
                YuuniverseEconomyApi.clear(economy);boolean unavailable=false;
                try{service.reconcile(console);}catch(IllegalStateException expected){unavailable=true;}finally{YuuniverseEconomyApi.publish(economy);}
                check(unavailable && service.claims(console).equals(originals) && economy.balance(owner,currency.id())==balance,"unavailable_provider_cannot_silently_authorize_paid_reconciliation");
                boolean rejected=false;try{service.reconcile(console);}catch(IllegalStateException expected){if(consistent)throw expected;rejected=true;}
                check(consistent?!rejected:rejected,"paid_reconciliation_validates_original_credit_instead_of_silently_skipping_paid");
                check(service.claims(console).equals(originals) && economy.balance(owner,currency.id())==balance && runtime.ledger().audit().transactionCount()==count && team.isRewardClaimed(owner,money),"paid_validation_preserves_all_claims_native_markers_money_and_financial_history");
                rejected=false;try{team.claimReward(actor,later,false);}catch(IllegalStateException expected){if(consistent)throw expected;rejected=true;}
                check(consistent?!rejected && team.isRewardClaimed(owner,later) && economy.balance(owner,currency.id())==balance+25 && service.claims(console).size()==3
                        :rejected && !team.isRewardClaimed(owner,later) && economy.balance(owner,currency.id())==balance && service.claims(console).equals(originals),
                        "new_native_claim_validates_prior_paid_evidence_before_marking_preparing_or_paying");
                long after=economy.balance(owner,currency.id());
                if(consistent)check(service.reconcile(console)==0 && economy.balance(owner,currency.id())==after,"matching_paid_and_startup_settlements_reconcile_without_paying_again");
                else {
                    var intent=claim.intent();var replacement=economy.credit(owner,currency.id(),intent.binding().amount(),intent.operationKey(),intent.sourceReference());
                    check(!replacement.transactionId().equals(claim.transactionId()) && !transaction(database,claim.transactionId())
                            && economy.inspectCredit(owner,currency.id(),intent.binding().amount(),intent.operationKey(),intent.sourceReference()).orElseThrow().transactionId().equals(replacement.transactionId()),
                            "same_original_request_key_and_payload_can_have_a_different_transaction_after_restore");
                    long replacedBalance=economy.balance(owner,currency.id()),replacedCount=runtime.ledger().audit().transactionCount();
                    rejected=false;try{service.reconcile(console);}catch(IllegalStateException expected){rejected=true;}
                    boolean claimRejected=false;try{team.claimReward(actor,later,false);}catch(IllegalStateException expected){claimRejected=true;}
                    check(rejected && claimRejected && !team.isRewardClaimed(owner,later) && service.claims(console).equals(originals)
                            && economy.balance(owner,currency.id())==replacedBalance && runtime.ledger().audit().transactionCount()==replacedCount,
                            "different_transaction_identity_blocks_reconciliation_and_new_claim_even_with_matching_request_payload");
                    var identity=new JsonObject();identity.addProperty("run",state.get("run").getAsString());identity.addProperty("phase",phase);identity.addProperty("originalTransaction",claim.transactionId().toString());identity.addProperty("replacementTransaction",replacement.transactionId().toString());identity.addProperty("operationKey",intent.operationKey());
                    Files.writeString(server.getServerDirectory().resolve("financial-replacement-"+phase+".json"),identity.toString());
                    snapshot(database,server.getServerDirectory().resolve("financial-replacement-"+phase+".db"));
                }
                check(runtime.ledger().audit().valid(),"paid_restore_keeps_native_provider_ledger_balanced");
            }
            LOG.info("[FINANCIALAUDIT] COMPLETE {} {} checks run={}",passed,phase,System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));
        }catch(Throwable failure){LOG.error("[FINANCIALAUDIT] FAILED paid {}",phase,failure);}
        finally {if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static boolean transaction(Path database,UUID id)throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database);var statement=connection.prepareStatement("SELECT transaction_id FROM ledger_transaction WHERE transaction_id=?")){
            statement.setString(1,id.toString());try(var rows=statement.executeQuery()){return rows.next();}
        }
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[FINANCIALAUDIT] PASS {}",name);}
}
