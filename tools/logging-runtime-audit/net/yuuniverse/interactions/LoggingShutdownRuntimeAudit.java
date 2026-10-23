package net.yuuniverse.interactions;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.daqem.grieflogger.GriefLogger;
import com.daqem.grieflogger.config.GriefLoggerConfig;
import com.daqem.grieflogger.model.action.SessionAction;
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
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native observations created after the last tick, then normal shutdown and fresh restart. */
@EventBusSubscriber(modid="interactions")
public final class LoggingShutdownRuntimeAudit {
    private static final BlockPos STONE=new BlockPos(3,-60,3), SHAFT=new BlockPos(5,-60,3), CHEST=new BlockPos(7,-60,3), LATE=new BlockPos(11,-60,3);
    private static ServerPlayer actor;
    private static io.netty.channel.embedded.EmbeddedChannel channel;
    private static Connection observer;
    private static JsonObject state;
    private static JsonArray beforeRows;
    private static UUID dropped;
    private static boolean initialized, failed, recovery;
    private static int passed, joinsBefore, quitsBefore;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");

    private static boolean active() {
        var phase=System.getenv("CITIZENS_LOGGING_AUDIT_PHASE");
        return "shutdown".equals(phase)||"shutdown-recover".equals(phase);
    }

    public static void tick(ServerTickEvent.Post event) {
        if(initialized) return;
        var server=event.getServer(); var level=server.overworld();
        level.setChunkForced(0,0,true);
        if(server.getTickCount()<20||!level.isPositionEntityTicking(CHEST)) return;
        initialized=true;
        try {
            check(GriefLoggerConfig.queueFrequency.get()==100&&server.getTickCount()<80,"native_stop_occurs_between_configured_periodic_flushes");
            recovery="shutdown-recover".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"));
            state=recovery?JsonParser.parseString(Files.readString(server.getServerDirectory().resolve("logging-shutdown-result.json"))).getAsJsonObject():new JsonObject();
            observer=DriverManager.getConnection("jdbc:sqlite:"+server.getServerDirectory().resolve("database.db").toAbsolutePath());
            UUID id=recovery?UUID.fromString(state.get("actor").getAsString()):UUID.randomUUID();
            if(recovery) {
                check(rows(id).equals(state.get("committedRows")),"fresh_process_retains_exact_original_gameplay_observations");
                check(sessions(id,SessionAction.JOIN)==1&&sessions(id,SessionAction.QUIT)==1,"fresh_process_retains_original_native_join_and_shutdown_quit");
            }
            joinsBefore=sessions(id,SessionAction.JOIN); quitsBefore=sessions(id,SessionAction.QUIT);
            actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(id,"ShutdownActor"),ClientInformation.createDefault());
            actor.setPos(7.5,-60,5.5); channel=LoggingRuntimeAudit.connect(actor); actor.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
            if(recovery) {
                dropped=UUID.fromString(state.get("dropEntity").getAsString());
                check(storage(server).equals(state.get("storage")),"fresh_native_player_restores_exact_inventory_blocks_chest_and_original_drop");
                beforeRows=rows(id);
            } else {
                state.addProperty("actor",id.toString());
                place(CHEST,new ItemStack(Items.CHEST));
                var paper=new ItemStack(Items.PAPER,7); paper.set(DataComponents.CUSTOM_NAME,Component.literal("Shutdown paper"));
                ((Container)level.getBlockEntity(CHEST)).setItem(0,paper);
                GriefLogger.getDatabase().queue.execute(); GriefLogger.getDatabase().batchQueue.execute();
                check(GriefLogger.getDatabase().queue.pendingCount()+GriefLogger.getDatabase().batchQueue.pendingCount()==0,"native_seed_histories_commit_before_shutdown_boundary");
                beforeRows=rows(id);
                state.add("beforeRows",beforeRows);
            }
            server.halt(false);
        } catch(Throwable failure) { fail(failure); server.halt(false); }
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void stopping(ServerStoppingEvent event) {
        if(!active()||!initialized||failed) return;
        try {
            var server=event.getServer(); var level=server.overworld();
            if(!recovery) {
                actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                var hit=new BlockHitResult(Vec3.atCenterOf(CHEST),Direction.UP,CHEST,false);
                check(actor.gameMode.useItemOn(actor,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,hit).consumesAction(),"native_stopping_callback_opens_chest");
                actor.containerMenu.clicked(0,0,ClickType.PICKUP,actor); actor.closeContainer();
                check(((Container)level.getBlockEntity(CHEST)).getItem(0).isEmpty()&&actor.getInventory().countItem(Items.PAPER)==7,"native_stopping_callback_takes_original_component_paper");
                actor.getInventory().selected=8;
                place(STONE,new ItemStack(Items.STONE)); place(SHAFT,AllBlocks.SHAFT.asStack());
                var snow=new ItemStack(Items.SNOWBALL,3); snow.set(DataComponents.CUSTOM_NAME,Component.literal("Shutdown snow"));
                var expectedSnow=snow.copy();
                actor.setItemInHand(InteractionHand.MAIN_HAND,snow);
                var entity=actor.drop(actor.getInventory().removeItem(8,3),false,false);
                check(entity!=null&&ItemStack.matches(entity.getItem(),expectedSnow),"native_stopping_callback_drops_exact_component_stack");
                entity.setNoGravity(true); entity.setDeltaMovement(Vec3.ZERO); entity.setPickUpDelay(32767); dropped=entity.getUUID();
                state.addProperty("dropEntity",dropped.toString());
                place(LATE,new ItemStack(Items.OAK_PLANKS));
                check(GriefLogger.getDatabase().queue.pendingCount()>0&&GriefLogger.getDatabase().batchQueue.pendingCount()>0,"both_native_queues_are_pending_after_last_tick");
                check(rows(actor.getUUID()).equals(beforeRows),"independent_reader_sees_no_final_observations_before_stop_drain");
                NeoProtect.service().flush();
                check(NeoProtect.service().lookup(level,STONE,0,100).stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE).count()==1,"independent_h2_retains_native_stopping_placement");
                state.add("storage",storage(server));
                state.addProperty("ordinaryPendingAtStop",GriefLogger.getDatabase().queue.pendingCount());
                state.addProperty("batchPendingAtStop",GriefLogger.getDatabase().batchQueue.pendingCount());
                write(server.getServerDirectory().resolve("logging-shutdown-state.json"),state);
            } else check(storage(server).equals(state.get("storage")),"reconnect_and_second_stop_do_not_change_original_native_storage");
            server.getPlayerList().saveAll();
        } catch(Throwable failure) { fail(failure); }
    }

    @SubscribeEvent(priority=EventPriority.LOWEST)
    public static void stopped(ServerStoppedEvent event) {
        if(!active()||!initialized) return;
        try {
            if(failed) return;
            var database=GriefLogger.getDatabase();
            check(database.queue.pendingCount()==0&&database.batchQueue.pendingCount()==0,"normal_server_stop_drains_both_original_queues");
            var retained=rows(actor.getUUID());
            if(recovery) check(retained.equals(state.get("committedRows")),"second_stop_retains_each_original_gameplay_record_once");
            else {
                check(retained.size()==7,"normal_stop_commits_all_seven_original_gameplay_records");
                state.add("committedRows",retained);
            }
            check(sessions(actor.getUUID(),SessionAction.JOIN)==joinsBefore+1&&sessions(actor.getUUID(),SessionAction.QUIT)==quitsBefore+1,"normal_stop_commits_native_join_and_final_player_quit_once");
            state.addProperty(recovery?"recoveryChecks":"checks",passed);
            write(event.getServer().getServerDirectory().resolve(recovery?"logging-shutdown-recovery.json":"logging-shutdown-result.json"),state);
            LOG.info("[LOGGINGSHUTDOWN] COMPLETE {} checks phase={}",passed,recovery?"shutdown-recover":"shutdown");
            LOG.info("[LOGGINGAUDIT] COMPLETE native shutdown");
        } catch(Throwable failure) { fail(failure); }
        finally {
            try {if(observer!=null) observer.close();} catch(SQLException failure) {fail(failure);}
            if(channel!=null) channel.finishAndReleaseAll();
        }
    }

    private static int sessions(UUID owner,SessionAction action) throws SQLException {
        try(var query=observer.prepareStatement("SELECT COUNT(*) FROM sessions s JOIN users u ON s.user=u.id WHERE u.uuid=? AND s.action=?")) {
            query.setString(1,owner.toString()); query.setInt(2,action.getId());
            try(var rows=query.executeQuery()) {rows.next();return rows.getInt(1);}
        }
    }
    private static JsonArray rows(UUID owner) throws SQLException {
        var values=new JsonArray();
        for(var table:List.of("blocks","containers","items")) {
            var columns=table.equals("blocks")?"m.name,r.action,0,NULL":"m.name,r.action,r.amount,hex(r.data)";
            try(var query=observer.prepareStatement("SELECT r.time,r.x,r.y,r.z,"+columns+" FROM "+table+" r JOIN users u ON r.user=u.id JOIN materials m ON r.type=m.id WHERE u.uuid=? ORDER BY r.time,r.x,r.y,r.z,m.name,r.action")) {
                query.setString(1,owner.toString());
                try(var rows=query.executeQuery()) {
                    while(rows.next()) {var row=new JsonArray();row.add(table);for(int col=1;col<=8;col++) row.add(new Gson().toJsonTree(rows.getObject(col)));values.add(row);}
                }
            }
        }
        return values;
    }
    private static JsonObject storage(MinecraftServer server) {
        var level=server.overworld(); var ops=server.registryAccess().createSerializationContext(JsonOps.INSTANCE); var value=new JsonObject();
        var inventory=new JsonArray();for(int slot=0;slot<actor.getInventory().getContainerSize();slot++) inventory.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops,actor.getInventory().getItem(slot)).getOrThrow());value.add("inventory",inventory);
        var chest=new JsonArray();var container=(Container)level.getBlockEntity(CHEST);for(int slot=0;slot<container.getContainerSize();slot++) chest.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops,container.getItem(slot)).getOrThrow());value.add("chest",chest);
        var blocks=new JsonArray();for(var pos:List.of(STONE,SHAFT,CHEST,LATE)) blocks.add(net.minecraft.world.level.block.state.BlockState.CODEC.encodeStart(JsonOps.INSTANCE,level.getBlockState(pos)).getOrThrow());value.add("blocks",blocks);
        if(!(level.getEntity(dropped) instanceof ItemEntity entity)||!entity.isAlive()) throw new AssertionError("Original native shutdown drop missing");
        value.addProperty("dropEntity",entity.getUUID().toString());value.add("dropStack",ItemStack.CODEC.encodeStart(ops,entity.getItem()).getOrThrow());value.addProperty("dropNoGravity",entity.isNoGravity());return value;
    }
    private static void place(BlockPos pos,ItemStack stack) {
        var level=(ServerLevel)actor.level();actor.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+2.5);level.removeBlock(pos,false);actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var hit=new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0,0.5,0),Direction.UP,pos.below(),false);
        if(!actor.gameMode.useItemOn(actor,level,stack,InteractionHand.MAIN_HAND,hit).consumesAction()) throw new AssertionError("Native stopping placement refused");
    }
    private static void write(Path path,JsonObject state) throws Exception {
        try(var file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {var bytes=StandardCharsets.UTF_8.encode(state.toString());while(bytes.hasRemaining()) file.write(bytes);file.force(true);}
    }
    private static void check(boolean value,String name) {if(!value) throw new AssertionError(name);passed++;LOG.info("[LOGGINGSHUTDOWN] PASS {}",name);}
    private static void fail(Throwable failure) {failed=true;LOG.error("[LOGGINGAUDIT] FAILED native shutdown",failure);}
}
