package net.yuuniverse.interactions;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.daqem.grieflogger.GriefLogger;
import com.daqem.grieflogger.database.service.Services;
import com.daqem.grieflogger.model.action.*;
import com.daqem.grieflogger.model.history.ItemHistory;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.simibubi.create.AllBlocks;
import dev.northbladetech.neoprotect.NeoProtect;
import dev.northbladetech.neoprotect.model.ActionType;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real native producers, real SQLite lock, provider workers and independent H2 logging. */
public final class LoggingStorageRuntimeAudit {
    private static final BlockPos STONE = new BlockPos(3,-60,3), SHAFT = new BlockPos(5,-60,3), CHEST = new BlockPos(7,-60,3), LATE = new BlockPos(11,-60,3);
    private static ServerPlayer actor;
    private static io.netty.channel.embedded.EmbeddedChannel channel;
    private static Connection observer, blocker;
    private static boolean locked;
    private static int stage, readyTick, passed, ordinaryPending, batchPending;
    private static JsonObject state;
    private static JsonArray initialRows, committedRows;
    private static UUID dropped;

    public static void tick(ServerTickEvent.Post event) {
        var server=event.getServer(); var level=server.overworld();
        if(stage==99) return;
        level.setChunkForced(0,0,true);
        if(server.getTickCount()<20 || !level.isPositionEntityTicking(STONE)) return;
        try {
            var database=GriefLogger.getDatabase();
            if(stage==0) {
                stage=1;
                boolean recovery="storage-recover".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"));
                state=recovery ? JsonParser.parseString(Files.readString(server.getServerDirectory().resolve("logging-storage-state.json"))).getAsJsonObject() : new JsonObject();
                UUID owner=recovery ? UUID.fromString(state.get("actor").getAsString()) : UUID.randomUUID();
                actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(owner,"StorageActor"),ClientInformation.createDefault());
                actor.setPos(7.5,-60,5.5); channel=LoggingRuntimeAudit.connect(actor); actor.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                observer=DriverManager.getConnection("jdbc:sqlite:"+server.getServerDirectory().resolve("database.db").toAbsolutePath());
                if(recovery) { dropped=UUID.fromString(state.get("dropEntity").getAsString()); recover(server); return; }
                state.addProperty("actor",owner.toString());
                place(CHEST,new ItemStack(Items.CHEST));
                var paper=new ItemStack(Items.PAPER,7); paper.set(DataComponents.CUSTOM_NAME,Component.literal("Storage lock paper"));
                ((Container)level.getBlockEntity(CHEST)).setItem(0,paper);
                readyTick=server.getTickCount()+40; return;
            }
            if(server.getTickCount()<readyTick) return;
            if(stage==1) {
                database.queue.execute(); database.batchQueue.execute();
                if(database.queue.pendingCount()+database.batchQueue.pendingCount()!=0) { readyTick=server.getTickCount()+1; return; }
                initialRows=rows();
                check(initialRows.size()>0,"native_join_and_seed_chest_history_commit_before_lock");
                database.execute("PRAGMA busy_timeout=20",true);
                blocker=DriverManager.getConnection("jdbc:sqlite:"+server.getServerDirectory().resolve("database.db").toAbsolutePath());
                sql(blocker,"BEGIN IMMEDIATE"); locked=true;
                actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                var hit=new BlockHitResult(Vec3.atCenterOf(CHEST),Direction.UP,CHEST,false);
                check(actor.gameMode.useItemOn(actor,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,hit).consumesAction(),"native_menu_opens_during_sqlite_write_lock");
                actor.containerMenu.clicked(0,0,ClickType.PICKUP,actor); actor.closeContainer();
                check(((Container)level.getBlockEntity(CHEST)).getItem(0).isEmpty()&&actor.getInventory().countItem(Items.PAPER)==7,"native_container_take_completes_with_logging_database_locked");
                actor.getInventory().selected=8;
                place(STONE,new ItemStack(Items.STONE)); place(SHAFT,AllBlocks.SHAFT.asStack());
                var input=new ItemStack(Items.SNOWBALL,3); input.set(DataComponents.CUSTOM_NAME,Component.literal("Storage lock drop"));
                var expectedDrop=input.copy();
                actor.setItemInHand(InteractionHand.MAIN_HAND,input);
                var entity=actor.drop(actor.getInventory().removeItem(actor.getInventory().selected,3),false,false);
                check(entity!=null&&ItemStack.matches(entity.getItem(),expectedDrop),"native_player_drop_returns_original_component_stack_during_lock");
                entity.setNoGravity(true); entity.setDeltaMovement(Vec3.ZERO); entity.setPickUpDelay(32767); dropped=entity.getUUID();
                state.addProperty("dropEntity",dropped.toString());
                stage=2; readyTick=server.getTickCount()+8; return;
            }
            if(stage==2) {
                database.queue.execute(); database.batchQueue.execute();
                ordinaryPending=database.queue.pendingCount(); batchPending=database.batchQueue.pendingCount();
                check(ordinaryPending>0&&batchPending>0,"native_ordinary_and_batch_writes_remain_queued_after_real_locked_flush");
                check(rows().equals(initialRows),"independent_sqlite_reader_sees_no_false_outage_commit");
                NeoProtect.service().flush();
                check(neoPlacements(STONE)==1&&neoPlacements(SHAFT)==1,"independent_neoprotect_h2_flushes_both_native_placements_during_sqlite_outage");
                check(NeoProtect.service().lookup(level,CHEST,0,100).stream().anyMatch(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.CONTAINER_TAKE&&e.itemCount()==7),"independent_neoprotect_retains_native_container_take_during_outage");
                place(LATE,new ItemStack(Items.OAK_PLANKS));
                state.add("storage",storage(server));
                stage=3; readyTick=server.getTickCount()+8; return;
            }
            if(stage==3) {
                database.queue.execute(); database.batchQueue.execute();
                check(database.queue.pendingCount()>ordinaryPending&&database.batchQueue.pendingCount()>=batchPending,"native_later_producer_appends_without_losing_failed_queue_prefixes");
                check(rows().equals(initialRows),"repeated_native_worker_retries_keep_locked_database_unchanged");
                check(storage(server).equals(state.get("storage")),"failed_flush_retries_change_no_native_world_inventory_or_drop");
                state.addProperty("lockedOrdinaryPending",database.queue.pendingCount()); state.addProperty("lockedBatchPending",database.batchQueue.pendingCount());
                state.add("beforeLockRows",initialRows);
                sql(blocker,"ROLLBACK"); locked=false; blocker.close(); blocker=null;
                stage=4; readyTick=server.getTickCount()+8; return;
            }
            if(stage==4) {
                database.queue.execute(); database.batchQueue.execute();
                if(database.queue.pendingCount()+database.batchQueue.pendingCount()!=0) {
                    if(server.getTickCount()>readyTick+80) throw new AssertionError("Native queues did not drain after unlock");
                    return;
                }
                check(true,"native_background_and_explicit_flushes_drain_after_actual_lock_release");
                histories(level);
                check(storage(server).equals(state.get("storage")),"successful_retry_changes_no_native_items_blocks_or_original_drop_identity");
                committedRows=rows(); state.add("committedRows",committedRows);
                stage=5; readyTick=server.getTickCount()+8; return;
            }
            if(stage==5) {
                database.queue.execute(); database.batchQueue.execute(); NeoProtect.service().flush();
                check(rows().equals(committedRows),"successful_native_retry_and_worker_ticks_do_not_duplicate_committed_records");
                check(neoPlacements(LATE)==1,"independent_logger_retains_later_outage_placement_once");
                check(storage(server).equals(state.get("storage")),"post_commit_retry_retains_exact_native_storage");
                state.addProperty("checks",passed);
                write(server.getServerDirectory().resolve("logging-storage-state.json"),state);
                server.getPlayerList().saveAll(); server.saveEverything(true,true,true);
                complete(server,"storage");
            }
        } catch(Throwable failure) {
            org.slf4j.LoggerFactory.getLogger("interactions").error("[LOGGINGAUDIT] FAILED native storage",failure);
            finish(server);
        }
    }
    private static void recover(MinecraftServer server) throws Exception {
        check(storage(server).equals(state.get("storage")),"restart_restores_exact_native_inventory_blocks_and_original_component_drop");
        check(rows().equals(state.get("committedRows")),"restart_retains_each_original_sqlite_record_after_outage_retry");
        check(GriefLogger.getDatabase().batchQueue.pendingCount()==0,"restart_does_not_recreate_already_committed_native_item_batches");
        histories(server.overworld());
        GriefLogger.getDatabase().queue.execute(); GriefLogger.getDatabase().batchQueue.execute();
        check(rows().equals(state.get("committedRows")),"restart_flush_does_not_duplicate_original_outage_records");
        check(storage(server).equals(state.get("storage")),"restart_log_queries_and_flushes_do_not_replay_native_item_effects");
        var result=state.deepCopy(); result.add("recoveredStorage",storage(server)); result.add("recoveredRows",rows()); result.addProperty("recoveryChecks",passed);
        write(server.getServerDirectory().resolve("logging-storage-recovery.json"),result);
        complete(server,"storage-recover");
    }
    private static void histories(ServerLevel level) {
        for(var pos:List.of(STONE,SHAFT,LATE)) check(Services.BLOCK.getBlockHistory(level,pos).stream().filter(e->e.getUser().getUuid().filter(actor.getUUID()::equals).isPresent()&&e.getAction()==BlockAction.PLACE_BLOCK).count()==1,"grieflogger_recovers_exact_native_placement_once_at_"+pos.toShortString());
        var containers=Services.CONTAINER.getHistory(level,CHEST).stream().filter(e->e.getUser().getUuid().filter(actor.getUUID()::equals).isPresent()&&e.getAction()==ItemAction.REMOVE_ITEM).toList();
        check(containers.size()==1&&containers.getFirst() instanceof ItemHistory item&&item.getItemStack().getCount()==7&&item.getItemStack().toItemStack().getHoverName().getString().equals("Storage lock paper"),"grieflogger_recovers_one_native_container_batch_with_exact_components");
        var filters=new com.daqem.grieflogger.command.filter.FilterList(List.of(new com.daqem.grieflogger.command.filter.RadiusFilter(30)),actor.createCommandSourceStack());
        var items=Services.ITEM.getFilteredItemHistory(level,filters).stream().filter(e->e.getUser().getUuid().filter(actor.getUUID()::equals).isPresent()&&e.getAction()==ItemAction.DROP_ITEM).toList();
        check(items.size()==1&&items.getFirst().getItemStack().getCount()==3&&items.getFirst().getItemStack().toItemStack().getHoverName().getString().equals("Storage lock drop"),"grieflogger_recovers_one_native_drop_batch_with_exact_components");
        check(neoPlacements(STONE)==1&&neoPlacements(SHAFT)==1&&neoPlacements(LATE)==1,"both_log_owners_agree_on_all_three_original_outage_placements");
    }
    private static long neoPlacements(BlockPos pos) { return NeoProtect.service().lookup((ServerLevel)actor.level(),pos,0,100).stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE).count(); }
    private static JsonArray rows() throws SQLException {
        var rows=new JsonArray();
        for(var table:List.of("blocks","containers","items")) {
            String columns=table.equals("blocks")?"m.name,r.action,0,NULL":"m.name,r.action,r.amount,hex(r.data)";
            String query="SELECT r.time,r.x,r.y,r.z,"+columns+" FROM "+table+" r JOIN users u ON r.user=u.id JOIN materials m ON r.type=m.id WHERE u.uuid=? ORDER BY r.time,r.x,r.y,r.z,m.name,r.action";
            try(var statement=observer.prepareStatement(query)) {
                statement.setString(1,actor.getUUID().toString());
                try(var results=statement.executeQuery()) {
                    while(results.next()) {
                        var row=new JsonArray(); row.add(table);
                        for(int column=1;column<=8;column++) { var value=results.getObject(column); row.add(value==null?JsonNull.INSTANCE:new Gson().toJsonTree(value)); }
                        rows.add(row);
                    }
                }
            }
        }
        return rows;
    }
    private static JsonObject storage(MinecraftServer server) {
        var level=server.overworld(); var ops=server.registryAccess().createSerializationContext(JsonOps.INSTANCE); var result=new JsonObject();
        var inventory=new JsonArray(); for(int slot=0;slot<actor.getInventory().getContainerSize();slot++) inventory.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops,actor.getInventory().getItem(slot)).getOrThrow()); result.add("inventory",inventory);
        var chest=new JsonArray(); var container=(Container)level.getBlockEntity(CHEST); for(int slot=0;slot<container.getContainerSize();slot++) chest.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops,container.getItem(slot)).getOrThrow()); result.add("chest",chest);
        var blocks=new JsonArray(); for(var pos:List.of(STONE,SHAFT,CHEST,LATE)) blocks.add(net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(JsonOps.INSTANCE,level.getBlockState(pos)).getOrThrow()); result.add("blocks",blocks);
        if(!(level.getEntity(dropped) instanceof ItemEntity entity)||!entity.isAlive()) throw new AssertionError("Original native drop missing");
        result.addProperty("dropEntity",entity.getUUID().toString()); result.addProperty("dropNoGravity",entity.isNoGravity()); result.add("dropStack",ItemStack.CODEC.encodeStart(ops,entity.getItem()).getOrThrow());
        return result;
    }
    private static void place(BlockPos pos,ItemStack stack) {
        var level=(ServerLevel)actor.level(); actor.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+2.5); level.removeBlock(pos,false); actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var hit=new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0,0.5,0),Direction.UP,pos.below(),false);
        if(!actor.gameMode.useItemOn(actor,level,stack,InteractionHand.MAIN_HAND,hit).consumesAction()) throw new AssertionError("Native placement refused");
    }
    private static void sql(Connection connection,String query) throws SQLException { try(var statement=connection.createStatement()) { statement.execute(query); } }
    private static void write(Path path,JsonObject state) throws Exception {
        try(var file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) { var bytes=StandardCharsets.UTF_8.encode(state.toString()); while(bytes.hasRemaining()) file.write(bytes); file.force(true); }
    }
    private static void check(boolean value,String name) { if(!value) throw new AssertionError(name); passed++; org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGSTORAGE] PASS {}",name); }
    private static void complete(MinecraftServer server,String phase) { org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGSTORAGE] COMPLETE {} checks phase={}",passed,phase); org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGAUDIT] COMPLETE native storage"); finish(server); }
    private static void finish(MinecraftServer server) {
        stage=99;
        try { if(blocker!=null) { if(locked) sql(blocker,"ROLLBACK"); blocker.close(); } if(observer!=null) observer.close(); }
        catch(SQLException failure) { org.slf4j.LoggerFactory.getLogger("interactions").error("Storage fixture close failed",failure); }
        if(actor!=null&&actor.connection!=null) server.getPlayerList().remove(actor); if(channel!=null) channel.finishAndReleaseAll(); server.halt(false);
    }
}
