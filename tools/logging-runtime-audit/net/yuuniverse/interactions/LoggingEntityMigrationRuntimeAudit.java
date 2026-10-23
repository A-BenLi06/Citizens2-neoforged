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
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Read-only acceptance of original histories through a native schema migration and restart. */
public final class LoggingEntityMigrationRuntimeAudit {
    private static final Gson GSON=new Gson();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static boolean started;
    private static int checks;
    public static void tick(ServerTickEvent.Post event) {
        if(started||event.getServer().getTickCount()<20)return;
        started=true;var server=event.getServer();var directory=server.getServerDirectory();
        boolean recover="entities-migrate-recover".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"));
        try(var observer=GriefLoggerConfig.useMysql.get()?DriverManager.getConnection("jdbc:mysql://"+GriefLoggerConfig.mysqlHost.get()+":"+GriefLoggerConfig.mysqlPort.get()+"/"+GriefLoggerConfig.mysqlDatabase.get(),GriefLoggerConfig.mysqlUsername.get(),GriefLoggerConfig.mysqlPassword.get()):DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("database.db").toAbsolutePath())) {
            check(GriefLogger.getDatabase().flushPendingWrites(),"migrated_schema_reports_ready_and_commits_startup");
            var original=read(directory.resolve("logging-entity-migration-original.json"));
            var actual=snapshot(observer);
            check(actual.equals(original),"native_migration_preserves_every_original_history_and_registry_value");
            var filters=new FilterList(List.of(),server.createCommandSourceStack());var radius=new RadiusFilter(30);radius.setPosition(new com.daqem.grieflogger.model.BlockPosition(7,-60,3));filters.setRadiusFilter(radius);
            var history=Services.BLOCK.getFilteredBlockHistory(server.overworld(),filters);
            check(history.size()==original.getAsJsonArray("blocks").size(),"native_public_lookup_returns_all_original_block_interaction_and_entity_events");
            long kills=original.getAsJsonArray("blocks").asList().stream().filter(value->value.getAsJsonArray().get(7).getAsInt()==3).count();
            check(kills>0&&history.stream().filter(value->value.getAction()==BlockAction.KILL_ENTITY&&value.getMaterialComponent().getString().equals("pig")).count()==kills,"native_public_lookup_resolves_original_entity_ids_to_real_registry");
            Services.BLOCK.createTable();
            check(GriefLogger.getDatabase().completeInitialization()&&snapshot(observer).equals(actual),"native_repeated_schema_initialization_preserves_original_history");
            var state=new JsonObject();state.add("tables",actual);state.addProperty("checks",checks);
            write(directory.resolve(recover?"logging-entity-migration-recovery.json":"logging-entity-migration-state.json"),state);
            LOG.info("[LOGGINGENTITYMIGRATION] COMPLETE {} checks phase={}",checks,recover?"entities-migrate-recover":"entities-migrate");
            LOG.info("[LOGGINGAUDIT] COMPLETE native entity schema migration");
        }catch(Throwable failure){LOG.error("[LOGGINGAUDIT] FAILED native entity migration",failure);}
        server.halt(false);
    }
    private static JsonObject snapshot(Connection db)throws SQLException {
        var result=new JsonObject();for(String table:List.of("materials","entities","blocks")){var values=new JsonArray();String columns=table.equals("blocks")?"time,user,level,x,y,z,type,action":"*";try(var query=db.createStatement();var rows=query.executeQuery("SELECT "+columns+" FROM "+table+" ORDER BY "+(table.equals("blocks")?"time,x,y,z,action":"id"))){while(rows.next()){var row=new JsonArray();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.add(GSON.toJsonTree(rows.getObject(i)));values.add(row);}}result.add(table,values);}return result;
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    private static void write(Path path,JsonObject value)throws Exception{Files.writeString(path,value.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;LOG.info("[LOGGINGENTITYMIGRATION] PASS {}",name);}
}
