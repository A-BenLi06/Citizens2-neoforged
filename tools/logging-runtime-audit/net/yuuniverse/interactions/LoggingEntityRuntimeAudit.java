package net.yuuniverse.interactions;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.daqem.grieflogger.GriefLogger;
import com.daqem.grieflogger.config.GriefLoggerConfig;
import com.daqem.grieflogger.command.filter.*;
import com.daqem.grieflogger.database.service.Services;
import com.daqem.grieflogger.model.action.BlockAction;
import com.google.gson.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native entity deaths before material IDs exist, then block/entity history and restart. */
public final class LoggingEntityRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static final Gson GSON=new Gson();
    private static boolean started;
    private static int checks;
    public static void tick(ServerTickEvent.Post event) {
        if(started||event.getServer().getTickCount()<20)return;
        started=true;var server=event.getServer();var directory=server.getServerDirectory();
        boolean recover="entities-recover".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"));
        ServerPlayer actor=null;io.netty.channel.embedded.EmbeddedChannel channel=null;
        try(var observer=observer(directory)) {
            check(GriefLogger.getDatabase().flushPendingWrites(),"startup_logging_commits_before_native_entities");
            if(recover) {
                var state=read(directory.resolve("logging-entity-state.json"));
                check(snapshot(observer).equals(state.get("tables")),"restart_preserves_every_exact_material_entity_and_block_row");
                actor=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(UUID.fromString(state.get("actor").getAsString()),"EntityActor"),ClientInformation.createDefault());
                actor.setPos(4,-60,4);channel=LoggingRuntimeAudit.connect(actor);
                verifyPublic(actor,state.getAsJsonArray("kills"));
                state=new JsonObject();state.addProperty("checks",checks);write(directory.resolve("logging-entity-recovery.json"),state);
            } else {
                var state=new JsonObject();state.add("original",snapshot(observer));
                check(count(observer,"materials")==0&&count(observer,"entities")==0&&count(observer,"blocks")==0,"fresh_fixture_has_no_material_or_entity_id_overlap");
                actor=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"EntityActor"),ClientInformation.createDefault());
                actor.setPos(4,-60,4);channel=LoggingRuntimeAudit.connect(actor);
                state.addProperty("actor",actor.getUUID().toString());var kills=new JsonArray();state.add("kills",kills);
                write(directory.resolve("logging-entity-attempt.json"),state);
                for(var type:List.of(EntityType.PIG,EntityType.COW,EntityType.SHEEP))kill(actor,type,kills);
                boolean flushed=GriefLogger.getDatabase().flushPendingWrites();
                state.addProperty("flushed",flushed);state.add("afterFirstKills",snapshot(observer));
                state.addProperty("pending",GriefLogger.getDatabase().queue.pendingCount()+GriefLogger.getDatabase().batchQueue.pendingCount());
                write(directory.resolve("logging-entity-observed.json"),state);
                check(flushed,"native_entity_queue_reports_committed");
                check(count(observer,"blocks")==3,"all_native_kills_persist_without_material_dictionary_rows");
                check(count(observer,"materials")==0&&count(observer,"entities")==3,"native_kills_create_only_real_entity_dictionary_entries");
                actor.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                var pos=new BlockPos(7,-60,3);var stack=new ItemStack(Items.STONE);
                server.overworld().setBlockAndUpdate(pos.below(),Blocks.BEDROCK.defaultBlockState());
                actor.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stack);
                actor.gameMode.useItemOn(actor,server.overworld(),stack,net.minecraft.world.InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos.below()),Direction.UP,pos.below(),false));
                check(server.overworld().getBlockState(pos).is(Blocks.STONE),"actual_native_material_placement_succeeds_after_entity_ids");
                actor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
                kill(actor,EntityType.PIG,kills);kill(actor,EntityType.CHICKEN,kills);
                check(GriefLogger.getDatabase().flushPendingWrites(),"mixed_native_material_and_entity_events_commit");
                check(count(observer,"materials")==1&&count(observer,"entities")==4&&count(observer,"blocks")==6,"mixed_history_retains_exact_real_dictionaries_and_all_native_events");
                verifyPublic(actor,kills);
                state.add("tables",snapshot(observer));state.addProperty("checks",checks);
                write(directory.resolve("logging-entity-state.json"),state);
            }
            LOG.info("[LOGGINGENTITY] COMPLETE {} checks phase={}",checks,recover?"entities-recover":"entities");
            LOG.info("[LOGGINGAUDIT] COMPLETE native entity history");
        }catch(Throwable failure){LOG.error("[LOGGINGAUDIT] FAILED native entity history",failure);}
        finally{if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static void kill(ServerPlayer actor,EntityType<? extends LivingEntity> type,JsonArray kills) {
        var entity=type.create(actor.serverLevel());int z=6+kills.size();entity.setPos(4,-60,z);actor.serverLevel().addFreshEntity(entity);
        entity.hurt(actor.serverLevel().damageSources().playerAttack(actor),1000);
        check(!entity.isAlive(),"actual_player_attack_kills_"+net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type));
        var value=new JsonObject();value.addProperty("entity",entity.getUUID().toString());value.addProperty("type",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString().replace("minecraft:",""));value.addProperty("x",4);value.addProperty("y",-60);value.addProperty("z",z);kills.add(value);
    }
    private static void verifyPublic(ServerPlayer actor,JsonArray kills) {
        var filters=new FilterList(List.of(new RadiusFilter(30)),actor.createCommandSourceStack());
        var history=Services.BLOCK.getFilteredBlockHistory(actor.serverLevel(),filters);
        check(history.stream().filter(row->row.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&row.getAction()==BlockAction.KILL_ENTITY).count()==kills.size(),"public_lookup_returns_every_native_entity_death_once");
        for(var value:kills){var kill=value.getAsJsonObject();check(history.stream().anyMatch(row->row.getAction()==BlockAction.KILL_ENTITY&&row.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&row.getPosition().x()==kill.get("x").getAsInt()&&row.getPosition().y()==kill.get("y").getAsInt()&&row.getPosition().z()==kill.get("z").getAsInt()&&row.getMaterialComponent().getString().equals(kill.get("type").getAsString())),"public_lookup_preserves_actual_entity_type_actor_position_"+kill.get("entity").getAsString());}
        check(Services.BLOCK.getBlockHistory(actor.serverLevel(),new BlockPos(7,-60,3)).stream().filter(row->row.getAction()==BlockAction.PLACE_BLOCK&&row.getUser().getUuid().orElseThrow().equals(actor.getUUID())).count()==1,"ordinary_block_lookup_keeps_native_material_event_separate");
    }
    private static Connection observer(Path directory)throws SQLException {
        return GriefLoggerConfig.useMysql.get()?DriverManager.getConnection("jdbc:mysql://"+GriefLoggerConfig.mysqlHost.get()+":"+GriefLoggerConfig.mysqlPort.get()+"/"+GriefLoggerConfig.mysqlDatabase.get(),GriefLoggerConfig.mysqlUsername.get(),GriefLoggerConfig.mysqlPassword.get()):DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("database.db").toAbsolutePath());
    }
    private static long count(Connection db,String table)throws SQLException{try(var query=db.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();return rows.getLong(1);}}
    private static JsonObject snapshot(Connection db)throws SQLException {
        var result=new JsonObject();for(String table:List.of("materials","entities","blocks")){var values=new JsonArray();try(var query=db.createStatement();var rows=query.executeQuery("SELECT * FROM "+table+" ORDER BY "+(table.equals("blocks")?"time,x,y,z,action":"id"))){int columns=rows.getMetaData().getColumnCount();while(rows.next()){var row=new JsonArray();for(int i=1;i<=columns;i++)row.add(GSON.toJsonTree(rows.getObject(i)));values.add(row);}}result.add(table,values);}return result;
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    private static void write(Path path,JsonObject value)throws Exception{Files.writeString(path,value.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;LOG.info("[LOGGINGENTITY] PASS {}",name);}
}
