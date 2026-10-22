package net.citizensnpcs.audit;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.UUID;
import com.google.gson.*;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import net.citizensnpcs.util.CommerceQuestTasks;
import net.citizensnpcs.util.CommerceTaskJournal.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.yuuniverse.economy.api.*;
import net.yuuniverse.economy.core.*;

/** Real native receipt reconciliation after stopped, individually selected financial backups. */
@EventBusSubscriber(modid="citizens")
public final class FinancialBackupRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static boolean ran;
    private static int passed;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        String scope=System.getenv("CITIZENS_FINANCIAL_AUDIT_SCOPE");
        if (scope!=null && !"actor".equals(scope)) return;
        if(ran || event.getServer().getTickCount()<20)return;
        ran=true;
        var server=event.getServer(); ServerPlayer actor=null; io.netty.channel.embedded.EmbeddedChannel channel=null;
        String phase=System.getenv("CITIZENS_FINANCIAL_AUDIT_PHASE");
        try {
            Path statePath=server.getServerDirectory().resolve("financial-state.json");
            var state="seed".equals(phase)?new JsonObject():JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
            UUID actorId="seed".equals(phase)?UUID.randomUUID():UUID.fromString(state.get("actor").getAsString());
            var file=ServerQuestFile.getInstance().orElseThrow();
            if(!"seed".equals(phase)) {
                // CustomTask maximum is runtime configuration, as with the public XMod callback.
                ((CustomTask)file.getTask(Long.parseUnsignedLong(state.get("pendingTask").getAsString(),16))).setMaxProgress(3);
                file.clearCachedData();file.clearCachedProgress();
            }
            actor=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(actorId,"FinancialActor"),ClientInformation.createDefault());
            actor.setPos(1,-60,1);channel=connect(actor);
            var source=actor.createCommandSourceStack().withPermission(2); var service=CommerceQuestTasks.current(server);
            var economy=YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();
            var runtime=(net.yuuniverse.economy.EconomyRuntime)economy;
            var team=file.getTeamData(actor).orElseThrow();
            if("seed".equals(phase)) {
                economy.ensurePlayerAccount(actorId,actor.getGameProfile().getName());
                var currency=economy.defaultCurrency();String shop="financial-bound-"+actorId, unrelated="financial-unbound-"+actorId;
                seed(runtime.ledger().databasePath(),shop,currency.id());seed(runtime.ledger().databasePath(),unrelated,currency.id());
                economy.credit(actorId,currency.id(),1000,"financial-seed:"+actorId,"fixture");
                actor.getInventory().clearContent();
                var chapter=new Chapter(file.newID(),file,file.getDefaultChapterGroup());chapter.onCreated();
                var quest=new Quest(file.newID(),chapter);quest.onCreated();var task=new CustomTask(file.newID(),quest);task.onCreated();
                var pendingQuest=new Quest(file.newID(),chapter);pendingQuest.onCreated();
                var pendingTask=new CustomTask(file.newID(),pendingQuest);pendingTask.onCreated();pendingTask.setMaxProgress(3);
                file.clearCachedData();file.clearCachedProgress();
                var binding=service.bind(source,task.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
                service.bind(source,pendingTask.getCodeString(),Shop.SYSTEM,shop,"paper",Action.BUY_FROM_SHOP,Measure.ITEMS,null,true);
                snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-before.db"));
                var outcome=trade(api,actor,shop,"seed");service.reconcile(source);
                var entry=service.entries(source).stream().filter(e->e.binding().equals(binding)&&e.receipt()!=null).findFirst().orElseThrow();
                check(outcome.state()==CommerceOutcome.State.COMMITTED && entry.state()==State.APPLIED && team.getProgress(task)==1,"actual_native_trade_is_paid_delivered_and_applied_before_backup");
                check(economy.balance(actorId,currency.id())==990 && actor.getInventory().countItem(Items.PAPER)==1,"seed_has_exact_native_money_and_item_outcomes");
                check(team.getProgress(pendingTask)==1 && !team.isCompleted(pendingTask),"second_native_binding_retains_an_incomplete_task_for_new_admission_checks");
                var position=api.journalPosition(actorId);
                state.addProperty("run",System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));state.addProperty("actor",actorId.toString());
                state.addProperty("task",task.getCodeString());state.addProperty("pendingTask",pendingTask.getCodeString());state.addProperty("boundShop",shop);
                state.addProperty("entry",entry.id());state.addProperty("operation",outcome.receipt().operationId().toString());
                state.addProperty("transaction",outcome.receipt().transactionId().toString());state.addProperty("currency",currency.id());
                state.addProperty("provider",position.journalId().toString());state.addProperty("sequence",position.sequence());state.addProperty("unrelatedShop",unrelated);
                Files.writeString(statePath,state.toString());snapshot(runtime.ledger().databasePath(),server.getServerDirectory().resolve("financial-after.db"));
                ((dev.ftb.mods.ftbteams.data.TeamManagerImpl)dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager()).saveNow();
                team.saveIfChanged();file.saveNow();server.saveEverything(true,true,true);
            } else {
                check(state.get("run").getAsString().equals(System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN")),"recovery_uses_the_original_fresh_fixture_identity");
                var task=file.getTask(Long.parseUnsignedLong(state.get("task").getAsString(),16));
                var pendingTask=file.getTask(Long.parseUnsignedLong(state.get("pendingTask").getAsString(),16));
                var original=service.entries(source).stream().filter(e->e.id().equals(state.get("entry").getAsString())).findFirst().orElseThrow();
                check(original.state()==State.APPLIED && team.getProgress(task)==1 && team.getProgress(pendingTask)==1
                        && actor.getInventory().countItem(Items.PAPER)==1,"native_ftb_consumer_and_player_saves_retain_original_applied_trade");
                UUID operation=UUID.fromString(state.get("operation").getAsString());
                if("consistent".equals(phase)) {
                    check(api.findOperation(actorId,operation).orElseThrow().receipt().transactionId().toString().equals(state.get("transaction").getAsString()),"matching_backup_restores_the_exact_original_financial_receipt");
                    check(service.reconcile(source)==0 && team.getProgress(task)==1 && economy.balance(actorId,state.get("currency").getAsString())==990,"matching_backup_reconciliation_does_not_charge_deliver_or_project_twice");
                    check(team.getProgress(pendingTask)==1 && service.entries(source).size()==2,"matching_backup_preserves_both_complete_and_incomplete_original_projections");
                } else {
                    check(api.findOperation(actorId,operation).isEmpty() && economy.balance(actorId,state.get("currency").getAsString())==1000,"older_financial_backup_has_no_original_settlement_and_restores_old_balance");
                    int catchup="higher".equals(phase)?2:"caughtup".equals(phase)?1:0;
                    for(int i=0;i<catchup;i++)trade(api,actor,state.get("unrelatedShop").getAsString(),"catchup-"+i);
                    var position=api.journalPosition(actorId);long old=state.get("sequence").getAsLong();
                    check(position.journalId().toString().equals(state.get("provider").getAsString())
                            && (catchup==0?position.sequence()<old:catchup==1?position.sequence()==old:position.sequence()>old),"selected_backup_has_expected_native_provider_identity_and_high_water");
                    check(api.findOperation(actorId,operation).isEmpty(),"unrelated_catchup_does_not_restore_the_missing_original_operation");
                    int items=actor.getInventory().countItem(Items.PAPER);long balance=economy.balance(actorId,state.get("currency").getAsString());
                    boolean rejected=false;
                    try {service.reconcile(source);}catch(IllegalStateException expected) {
                        if(!expected.getMessage().contains("regressed")&&!expected.getMessage().contains("disappeared"))throw expected;
                        LOG.info("[FINANCIALAUDIT] expected rejection: {}",expected.getMessage());rejected=true;
                    }
                    check(rejected,"inconsistent_financial_backup_is_rejected_even_after_unrelated_sequence_catchup");
                    check(service.entries(source).stream().filter(e->e.id().equals(original.id())).findFirst().orElseThrow().equals(original)
                            && team.getProgress(task)==1 && economy.balance(actorId,state.get("currency").getAsString())==balance
                            && actor.getInventory().countItem(Items.PAPER)==items,"rejection_preserves_retained_evidence_native_progress_money_and_inventory");
                    int evidenceBefore=service.entries(source).size();
                    var newTrade=trade(api,actor,state.get("boundShop").getAsString(),"after-rejection");
                    check(newTrade.state()==CommerceOutcome.State.COMMITTED && economy.balance(actorId,state.get("currency").getAsString())==balance-10
                            && actor.getInventory().countItem(Items.PAPER)==items+1,"native_economy_retains_ownership_of_trading_after_consumer_reconciliation_rejection");
                    check(service.entries(source).size()==evidenceBefore && team.getProgress(pendingTask)==1,
                            "missing_retained_settlement_blocks_new_consumer_attribution_and_incomplete_task_credit");
                }
                check(runtime.ledger().audit().valid(),"selected_financial_database_remains_natively_balanced");
            }
            LOG.info("[FINANCIALAUDIT] COMPLETE {} {} checks run={}",passed,phase,System.getenv("CITIZENS_FINANCIAL_AUDIT_RUN"));
        }catch(Throwable failure){LOG.error("[FINANCIALAUDIT] FAILED {}",phase,failure);}
        finally {if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    static CommerceOutcome trade(CommerceApi api,ServerPlayer actor,String shop,String suffix) {
        var request=new CommerceRequest(CommerceShopType.SYSTEM,shop,"paper",CommerceAction.BUY_FROM_SHOP,AccountIds.player(actor.getUUID()),1,"financial:"+UUID.randomUUID(),"audit:"+suffix);
        return api.execute(actor.getUUID(),api.quote(actor.getUUID(),request));
    }
    static void snapshot(Path database,Path target)throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+database);var statement=connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1,target.toAbsolutePath().toString());statement.execute();
        }
    }
    static void seed(Path database,String shop,String currency)throws Exception {
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+database);
            var s=c.prepareStatement("INSERT INTO system_shop VALUES (?,?,'fixture','fixture','{}',1)");
            var p=c.prepareStatement("INSERT INTO system_shop_page VALUES (?,'page1',0,'Financial fixture',3,'{}')");
            var o=c.prepareStatement("INSERT INTO system_shop_item (shop_id,item_key,page_key,slot_key,legacy_material,item_config_json,currency_id,buy_price_minor_units,sell_price_minor_units,dynamic_pricing,enabled) VALUES (?,'paper','page1','1','PAPER','{\"material\":\"PAPER\"}',?,10,4,0,1)")) {
            s.setString(1,shop);s.setString(2,shop);s.executeUpdate();p.setString(1,shop);p.executeUpdate();o.setString(1,shop);o.setString(2,currency);o.executeUpdate();
        }
    }
    static io.netty.channel.embedded.EmbeddedChannel connect(ServerPlayer player) {
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel=new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
            @Override protected void initChannel(io.netty.channel.Channel channel) {net.minecraft.network.Connection.configureInMemoryPipeline(channel.pipeline(),net.minecraft.network.protocol.PacketFlow.SERVERBOUND);connection.configurePacketHandler(channel.pipeline());}
        });
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        var cookie=new net.minecraft.server.network.CommonListenerCookie(player.getGameProfile(),0,ClientInformation.createDefault(),false,net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(net.minecraft.network.RegistryFriendlyByteBuf.decorator(player.registryAccess(),cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);return channel;
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[FINANCIALAUDIT] PASS {}",name);}
}
