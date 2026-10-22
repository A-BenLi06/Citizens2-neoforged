package net.citizensnpcs.audit.delivery;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.Items;

/** Actual saved-player checkpoints against a stopped, valid older journal prefix. */
final class DeliveryHistoryRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("citizens");
    private static final String TASK="00000000004D1003",OTHER="00000000004D1013",CHECKPOINT="citizens:quest_delivery_checkpoint";
    private static ServerPlayer interrupted;
    private static int passed;
    static void boundary(String point,ServerPlayer player){
        if(interrupted==player&&player!=null&&point.equals("inventory")){interrupted=null;throw new IllegalStateException("Fixture interrupts native tail before confirmation");}
    }
    static void run(MinecraftServer server,String phase){
        var players=new ArrayList<ServerPlayer>();var channels=new ArrayList<EmbeddedChannel>();
        try{
            String shape=Objects.requireNonNullElse(System.getenv("CITIZENS_DELIVERY_AUDIT_SHAPE"),"absent");
            if(!Set.of("absent","resolved").contains(shape)||!Set.of("seed","missing","consistent").contains(phase))throw new IllegalArgumentException("Unknown delivery history phase/shape");
            var path=server.getServerDirectory().resolve("delivery-history-state.json");
            JsonObject state=phase.equals("seed")?new JsonObject():JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            var file=ServerQuestFile.getInstance().orElseThrow();var service=QuestDeliveryRecovery.current(server);var console=server.createCommandSourceStack();
            if(phase.equals("seed")){
                state.addProperty("shape",shape);state.addProperty("run",System.getenv("CITIZENS_DELIVERY_AUDIT_RUN"));
                var other=connect(server,UUID.randomUUID(),"HistoryOther",players,channels);other.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(2));FtbQuestTasks.submit(other,TASK,false);
                var old=latest(service,other);check(old.state()==QuestDeliveryJournal.State.CONFIRMED,"unrelated_actor_has_actual_confirmed_native_delivery");
                state.addProperty("other",other.getUUID().toString());state.addProperty("otherEntry",old.id().toString());state.addProperty("otherTeam",file.getTeamData(other).orElseThrow().getTeamId().toString());
                if(shape.equals("absent"))snapshot(server,"history-prefix");
                var actor=connect(server,UUID.randomUUID(),"HistoryActor",players,channels);actor.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(2));FtbQuestTasks.submit(actor,TASK,false);
                var first=latest(service,actor);check(first.state()==QuestDeliveryJournal.State.CONFIRMED,"original_actor_has_actual_confirmed_native_delivery");
                if(shape.equals("resolved")){
                    actor.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(2));interrupted=actor;
                    rejected(()->FtbQuestTasks.submit(actor,OTHER,false),"native_tail_failure_retains_uncertain_delivery");
                    var pending=latest(service,actor);check(pending.state()==QuestDeliveryJournal.State.REVIEW&&pending.after()==null,"interrupted_native_consumption_has_durable_review");
                    service.resolve(actor.createCommandSourceStack().withPermission(4),pending.id(),false,"Inspected original native partial consumption; retain effects without replay");
                    state.addProperty("resolvedPrefixEntry",pending.id().toString());snapshot(server,"history-prefix");
                    actor.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(1));FtbQuestTasks.submit(actor,TASK,false);first=latest(service,actor);
                }
                actor.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(shape.equals("absent")?3:2));
                var team=file.getTeamData(actor).orElseThrow();
                state.addProperty("actor",actor.getUUID().toString());state.addProperty("team",team.getTeamId().toString());state.addProperty("checkpoint",first.id().toString());state.addProperty("target",shape.equals("absent")?OTHER:TASK);
                state.addProperty("paper",actor.getInventory().countItem(Items.PAPER));state.addProperty("targetProgress",team.getProgress(file.getTask(FtbQuestTasks.parseId(state.get("target").getAsString()))));
                state.addProperty("originalEntries",service.entries(console).size());snapshot(server,"history-full");
                check(actor.getPersistentData().getString(CHECKPOINT).equals(first.id().toString()),"saved_checkpoint_identifies_last_confirmed_actor_outcome");
                check(service.entries(console).size()==(shape.equals("absent")?2:4),"prefix_and_full_histories_have_distinct_actual_global_tails");
                server.getPlayerList().saveAll();team.saveIfChanged();file.saveNow();Files.writeString(path,state.toString());
            }else{
                check(state.get("shape").getAsString().equals(shape)&&state.get("run").getAsString().equals(System.getenv("CITIZENS_DELIVERY_AUDIT_RUN")),"restart_reacquires_original_history_run");
                var actor=connect(server,UUID.fromString(state.get("actor").getAsString()),"HistoryActor",players,channels);var team=file.getTeamData(actor).orElseThrow();
                String task=state.get("target").getAsString(),checkpoint=actor.getPersistentData().getString(CHECKPOINT);int paper=actor.getInventory().countItem(Items.PAPER);long progress=team.getProgress(file.getTask(FtbQuestTasks.parseId(task)));
                check(checkpoint.equals(state.get("checkpoint").getAsString())&&paper==state.get("paper").getAsInt()&&progress==state.get("targetProgress").getAsLong(),"native_player_checkpoint_inventory_and_task_save_are_original");
                int count=service.entries(console).size();
                if(phase.equals("missing")){
                    check(service.entries(console).stream().noneMatch(e->e.id().toString().equals(checkpoint)),"valid_older_journal_prefix_lacks_saved_checkpoint_outcome");
                    var previous=service.entries(console).stream().filter(e->e.actorId().equals(actor.getUUID())).max(Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)).orElse(null);
                    check(shape.equals("absent")?previous==null:previous!=null&&previous.state()==QuestDeliveryJournal.State.RESOLVED,"prefix_has_expected_absent_or_resolved_latest_actor_history");
                    boolean preflight=false,execution=false;try{FtbQuestTasks.submit(actor,task,true);}catch(IllegalStateException expected){preflight=true;}try{FtbQuestTasks.submit(actor,task,false);}catch(IllegalStateException expected){execution=true;}
                    var result=new JsonObject();result.addProperty("phase",phase);result.addProperty("shape",shape);result.addProperty("actor",actor.getUUID().toString());result.addProperty("checkpointBefore",checkpoint);result.addProperty("checkpointAfter",actor.getPersistentData().getString(CHECKPOINT));result.addProperty("preflightRejected",preflight);result.addProperty("executionRejected",execution);result.addProperty("paperBefore",paper);result.addProperty("paperAfter",actor.getInventory().countItem(Items.PAPER));result.addProperty("progressBefore",progress);result.addProperty("progressAfter",team.getProgress(file.getTask(FtbQuestTasks.parseId(task))));result.addProperty("entriesBefore",count);result.addProperty("entriesAfter",service.entries(console).size());Files.writeString(server.getServerDirectory().resolve("delivery-history-missing-observation.json"),result.toString());
                    check(preflight&&execution,"missing_checkpoint_history_fences_preflight_and_execution");
                    check(actor.getInventory().countItem(Items.PAPER)==paper&&team.getProgress(file.getTask(FtbQuestTasks.parseId(task)))==progress&&service.entries(console).size()==count&&actor.getPersistentData().getString(CHECKPOINT).equals(checkpoint),"missing_history_neither_consumes_nor_fabricates_an_outcome");
                    for(String marker:List.of("not-a-UUID",state.get("otherEntry").getAsString())){
                        actor.getPersistentData().putString(CHECKPOINT,marker);rejected(()->FtbQuestTasks.submit(actor,task,true),"malformed_or_foreign_checkpoint_cannot_pass_preflight");rejected(()->FtbQuestTasks.submit(actor,task,false),"malformed_or_foreign_checkpoint_cannot_consume");
                    }
                    actor.getPersistentData().putInt(CHECKPOINT,42);rejected(()->FtbQuestTasks.submit(actor,task,false),"wrong_typed_checkpoint_is_not_a_fresh_actor");actor.getPersistentData().putString(CHECKPOINT,checkpoint);
                    var other=connect(server,UUID.fromString(state.get("other").getAsString()),"HistoryOther",players,channels);other.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(3));FtbQuestTasks.submit(other,TASK,false);
                    check(other.getInventory().countItem(Items.PAPER)==0&&latest(service,other).previousActorEntry().toString().equals(state.get("otherEntry").getAsString()),"valid_unrelated_actor_can_continue_its_original_chain");
                    var fresh=connect(server,UUID.randomUUID(),"HistoryFresh",players,channels);fresh.getInventory().setItem(0,DeliveryRuntimeAudit.parcel(1));FtbQuestTasks.submit(fresh,OTHER,false);
                    check(!fresh.getUUID().equals(actor.getUUID())&&latest(service,fresh).previousActorEntry()==null&&fresh.getInventory().countItem(Items.PAPER)==0,"genuinely_new_actor_without_checkpoint_can_deliver");
                }else{
                    check(service.entries(console).stream().anyMatch(e->e.id().toString().equals(checkpoint)&&e.after()!=null),"matching_backup_retains_original_confirmed_checkpoint_outcome");
                    FtbQuestTasks.submit(actor,task,true);FtbQuestTasks.submit(actor,task,false);var entry=latest(service,actor);
                    check(entry.state()==QuestDeliveryJournal.State.CONFIRMED&&entry.previousActorEntry().toString().equals(checkpoint)&&actor.getInventory().countItem(Items.PAPER)==0&&service.entries(console).size()==count+1,"matching_backup_allows_one_new_delivery_with_original_predecessor");
                    if(team.isCompleted(file.getTask(FtbQuestTasks.parseId(task)))){int retained=service.entries(console).size();FtbQuestTasks.submit(actor,task,false);check(service.entries(console).size()==retained,"completed_retry_does_not_repeat_delivery_after_history_restore");}
                }
            }
            LOG.info("[DELIVERYAUDIT] COMPLETE {} history checks phase={} shape={}",passed,phase,shape);
        }catch(Throwable failure){LOG.error("[DELIVERYAUDIT] FAILED history {}",phase,failure);}
        finally{interrupted=null;for(var player:players)if(server.getPlayerList().getPlayer(player.getUUID())==player)server.getPlayerList().remove(player);channels.forEach(EmbeddedChannel::finishAndReleaseAll);server.halt(false);}
    }
    private static ServerPlayer connect(MinecraftServer server,UUID id,String name,List<ServerPlayer> players,List<EmbeddedChannel> channels){var player=new ServerPlayer(server,server.overworld(),new GameProfile(id,name),ClientInformation.createDefault());player.setPos(1,-60,1);players.add(player);channels.add(DeliveryRuntimeAudit.connect(player));return player;}
    private static QuestDeliveryJournal.Entry latest(QuestDeliveryRecovery service,ServerPlayer player){return service.entries(player.getServer().createCommandSourceStack()).stream().filter(e->e.actorId().equals(player.getUUID())).max(Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)).orElseThrow();}
    private static void snapshot(MinecraftServer server,String name)throws Exception{Path directory=server.getServerDirectory().resolve(name);Files.createDirectory(directory);try(var files=Files.list(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("citizens/quest-deliveries"))){for(Path path:files.filter(p->p.getFileName().toString().endsWith(".json")).toList())Files.copy(path,directory.resolve(path.getFileName()));}}
    private static void rejected(Runnable action,String name){try{action.run();throw new AssertionError(name);}catch(IllegalStateException expected){check(true,name);}}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;LOG.info("[DELIVERYHISTORYAUDIT] PASS {}",name);}
}
