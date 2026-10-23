package net.yuuniverse.interactions;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.daqem.grieflogger.GriefLogger;
import com.daqem.grieflogger.config.GriefLoggerConfig;
import com.google.gson.*;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native command events, real MySQL and complete text, with separate restart acceptance. */
public final class LoggingMysqlCommandRuntimeAudit {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static final Gson GSON=new Gson();
    private static boolean started;
    private static int checks;
    public static void tick(ServerTickEvent.Post event) {
        if(started||event.getServer().getTickCount()<20)return;
        started=true;var server=event.getServer();var directory=server.getServerDirectory();
        boolean recover="mysql-commands-recover".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"));
        boolean reject="mysql-schema-reject".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"));
        ServerPlayer actor=null;io.netty.channel.embedded.EmbeddedChannel channel=null;
        try(var observer=observer()) {
            check(GriefLoggerConfig.useMysql.get(),"actual_provider_uses_configured_mysql_backend");
            if(!reject)check(GriefLogger.getDatabase().flushPendingWrites(),"all_startup_records_commit_before_command_acceptance");
            JsonObject state;
            if(reject) {
                state=read(directory.resolve("logging-mysql-schema-original.json"));
                check(!GriefLogger.getDatabase().flushPendingWrites()&&!GriefLogger.getDatabase().completeInitialization(),"unsupported_schema_cannot_admit_logging_or_report_ready");
                check(allCommands(observer).equals(state.get("allCommands")),"unsupported_schema_rejection_preserves_every_original_command_row");
                check(column(observer).equals(state.get("column")),"unsupported_schema_rejection_preserves_original_column_attributes");
                state.addProperty("checks",checks);write(directory.resolve("logging-mysql-schema-rejection.json"),state);
            } else if(recover) {
                state=read(directory.resolve("logging-mysql-command-state.json"));
                check(allCommands(observer).equals(state.get("allCommands")),"restart_preserves_every_exact_original_and_new_command_row");
                check(commands(observer,state.get("actor").getAsString()).equals(state.get("commands")),"restart_retains_all_exact_native_command_components_and_ownership");
                var column=column(observer);check(column.equals(state.get("column")),"restart_retains_widened_column_and_original_charset_collation");
                state=new JsonObject();state.add("column",column);state.addProperty("checks",checks);
                write(directory.resolve("logging-mysql-command-recovery.json"),state);
            } else {
                state=new JsonObject();state.add("originalCommands",allCommands(observer));state.add("originalColumn",column(observer));
                var actorId=UUID.randomUUID();state.addProperty("actor",actorId.toString());
                var inputs=List.of("say MySQL short command", "say "+"quote' slash\\ component 汉🙂 ".repeat(32)+"tail", "say "+"审".repeat(23000)+" 'quoted' \\ 🧭 tail");
                check(inputs.stream().allMatch(value->value.length()<=32767),"all_commands_fit_native_packet_character_bound");
                check(inputs.get(2).getBytes(StandardCharsets.UTF_8).length>65535,"wide_command_exceeds_mysql_text_byte_capacity");
                state.add("inputs",GSON.toJsonTree(inputs));
                write(directory.resolve("logging-mysql-command-attempt.json"),state);
                actor=new ServerPlayer(server,server.overworld(),new com.mojang.authlib.GameProfile(actorId,"MysqlCommand"),ClientInformation.createDefault());
                actor.setPos(4,-60,4);channel=LoggingRuntimeAudit.connect(actor);
                var source=actor.createCommandSourceStack().withPermission(2);
                for(String command:inputs)server.getCommands().performPrefixedCommand(source,command);
                check(GriefLogger.getDatabase().flushPendingWrites(),"actual_native_command_event_queue_commits");
                var rows=commands(observer,actorId.toString());state.add("commands",rows);state.add("allCommands",allCommands(observer));state.add("column",column(observer));
                state.addProperty("pending",GriefLogger.getDatabase().queue.pendingCount()+GriefLogger.getDatabase().batchQueue.pendingCount());
                // Retain the actual truncated values too when the pre-fix assertion fails.
                write(directory.resolve("logging-mysql-command-observed.json"),state);
                check(rows.size()==inputs.size(),"one_row_per_actual_native_command_event");
                for(int i=0;i<inputs.size();i++) {
                    var row=rows.get(i).getAsJsonObject();
                    check(row.get("command").getAsString().equals(inputs.get(i)),"mysql_preserves_complete_native_command_"+i);
                    check(row.get("uuid").getAsString().equals(actorId.toString())&&row.get("name").getAsString().equals("MysqlCommand")
                            &&row.get("level").getAsString().equals("minecraft:overworld")&&row.get("x").getAsInt()==4&&row.get("z").getAsInt()==4,"mysql_preserves_native_command_actor_and_position_"+i);
                }
                var prior=state.getAsJsonArray("originalCommands");var all=state.getAsJsonArray("allCommands");
                var unchanged=new JsonArray();for(var row:all)if(!row.getAsJsonObject().get("uuid").getAsString().equals(actorId.toString()))unchanged.add(row);
                check(unchanged.equals(prior),"new_native_commands_preserve_every_preexisting_command_row");
                state.addProperty("checks",checks);write(directory.resolve("logging-mysql-command-state.json"),state);
            }
            LOG.info("[MYSQLCOMMANDAUDIT] COMPLETE {} checks phase={}",checks,reject?"mysql-schema-reject":recover?"mysql-commands-recover":"mysql-commands");
            LOG.info("[LOGGINGAUDIT] COMPLETE native MySQL command acceptance");
        } catch(Throwable failure){LOG.error("[LOGGINGAUDIT] FAILED native MySQL command acceptance",failure);}
        finally {if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    }
    private static Connection observer()throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://"+GriefLoggerConfig.mysqlHost.get()+":"+GriefLoggerConfig.mysqlPort.get()+"/"+GriefLoggerConfig.mysqlDatabase.get(),GriefLoggerConfig.mysqlUsername.get(),GriefLoggerConfig.mysqlPassword.get());
    }
    private static JsonArray commands(Connection db,String actor)throws SQLException {
        try(var query=db.prepareStatement("SELECT c.time,u.uuid,u.name,l.name,c.x,c.y,c.z,c.command FROM commands c JOIN users u ON c.user=u.id JOIN levels l ON c.level=l.id WHERE u.uuid=? ORDER BY c.time,c.command")){query.setString(1,actor);try(var rows=query.executeQuery()){return rows(rows);}}
    }
    private static JsonArray allCommands(Connection db)throws SQLException {
        try(var query=db.createStatement();var rows=query.executeQuery("SELECT c.time,u.uuid,u.name,l.name,c.x,c.y,c.z,c.command FROM commands c JOIN users u ON c.user=u.id JOIN levels l ON c.level=l.id ORDER BY c.time,u.uuid,c.command")){return rows(rows);}
    }
    private static JsonArray rows(ResultSet rows)throws SQLException {
        var result=new JsonArray();while(rows.next()){var row=new JsonObject();row.addProperty("time",rows.getLong(1));row.addProperty("uuid",rows.getString(2));row.addProperty("name",rows.getString(3));row.addProperty("level",rows.getString(4));row.addProperty("x",rows.getInt(5));row.addProperty("y",rows.getInt(6));row.addProperty("z",rows.getInt(7));row.addProperty("command",rows.getString(8));result.add(row);}return result;
    }
    private static JsonObject column(Connection db)throws SQLException {
        try(var query=db.createStatement();var rows=query.executeQuery("SELECT DATA_TYPE,CHARACTER_SET_NAME,COLLATION_NAME,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,COLUMN_COMMENT FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='commands' AND column_name='command'")) {
            if(!rows.next())throw new SQLException("Missing actual command column");var value=new JsonObject();
            for(int i=1;i<=7;i++)value.add(rows.getMetaData().getColumnLabel(i),GSON.toJsonTree(rows.getString(i)));return value;
        }
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    private static void write(Path path,JsonObject value)throws Exception{Files.writeString(path,value.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;LOG.info("[MYSQLCOMMANDAUDIT] PASS {}",name);}
}
