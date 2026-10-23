package net.yuuniverse.interactions;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.List;
import com.daqem.grieflogger.GriefLogger;
import com.daqem.grieflogger.config.GriefLoggerConfig;
import com.daqem.grieflogger.database.Database;
import com.google.gson.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** An unsupported original schema must remain untouched even by later startup DDL. */
public final class LoggingEntityRejectionRuntimeAudit {
    private static final Gson GSON=new Gson();
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static boolean started;
    private static int checks;
    public static void tick(ServerTickEvent.Post event) {
        if(started||event.getServer().getTickCount()<20)return;
        started=true;var server=event.getServer();var directory=server.getServerDirectory();
        boolean mysql=GriefLoggerConfig.useMysql.get();
        try(var observer=mysql?DriverManager.getConnection("jdbc:mysql://"+GriefLoggerConfig.mysqlHost.get()+":"+GriefLoggerConfig.mysqlPort.get()+"/"+GriefLoggerConfig.mysqlDatabase.get(),GriefLoggerConfig.mysqlUsername.get(),GriefLoggerConfig.mysqlPassword.get()):DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("database.db").toAbsolutePath())) {
            var original=JsonParser.parseString(Files.readString(directory.resolve("logging-entity-rejection-original.json"))).getAsJsonObject();
            check(!GriefLogger.getDatabase().completeInitialization()&&!GriefLogger.getDatabase().flushPendingWrites(),"unsupported_blocks_schema_cannot_report_ready_or_flush");
            check(schema(observer,mysql).equals(original.get("schema")),"rejected_schema_keeps_original_blocks_definition_and_missing_index");
            check(history(observer).equals(original.get("tables")),"rejected_schema_keeps_every_original_history_and_registry_row");
            var field=Database.class.getDeclaredField("connection");field.setAccessible(true);
            var owner=(Connection)field.get(GriefLogger.getDatabase());
            check(schema(owner,mysql).equals(original.get("schema")),"rejected_schema_has_no_uncommitted_optional_index_ddl_on_owning_connection");
            var state=new JsonObject();state.addProperty("checks",checks);state.add("schema",schema(observer,mysql));state.add("tables",history(observer));
            Files.writeString(directory.resolve("logging-entity-rejection.json"),state.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            LOG.info("[LOGGINGENTITYREJECTION] COMPLETE {} checks phase=entities-reject",checks);
            LOG.info("[LOGGINGAUDIT] COMPLETE native rejected entity schema");
        }catch(Throwable failure){LOG.error("[LOGGINGAUDIT] FAILED native rejected entity schema",failure);}
        server.halt(false);
    }
    private static JsonArray rows(Connection db,String sql)throws SQLException {
        var result=new JsonArray();try(var query=db.createStatement();var rows=query.executeQuery(sql)){while(rows.next()){var row=new JsonArray();for(int i=1;i<=rows.getMetaData().getColumnCount();i++){Object value=rows.getObject(i);row.add(value instanceof byte[] bytes?GSON.toJsonTree(java.util.HexFormat.of().withUpperCase().formatHex(bytes)):GSON.toJsonTree(value));}result.add(row);}}return result;
    }
    private static JsonArray schema(Connection db,boolean mysql)throws SQLException {
        return rows(db,mysql?"SHOW CREATE TABLE blocks":"SELECT type,name,sql FROM sqlite_master WHERE tbl_name='blocks' ORDER BY type,name");
    }
    private static JsonObject history(Connection db)throws SQLException {
        var result=new JsonObject();for(String table:List.of("users","usernames","levels","materials","entities","blocks","chats","commands","containers","items","sessions"))result.add(table,rows(db,"SELECT * FROM "+table+" ORDER BY "+(table.equals("blocks")?"time,x,y,z,action":table.equals("users")||table.equals("levels")||table.equals("materials")||table.equals("entities")?"id":"time")));return result;
    }
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;LOG.info("[LOGGINGENTITYREJECTION] PASS {}",name);}
}
