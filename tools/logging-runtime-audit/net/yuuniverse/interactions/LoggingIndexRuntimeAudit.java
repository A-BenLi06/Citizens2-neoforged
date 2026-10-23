package net.yuuniverse.interactions;

import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.daqem.grieflogger.GriefLogger;
import com.daqem.grieflogger.config.GriefLoggerConfig;
import com.daqem.grieflogger.database.service.Services;
import com.google.gson.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Fixture-owned SQL load through the real provider; no synthetic gameplay observations. */
public final class LoggingIndexRuntimeAudit {
    private static final List<String> HISTORY=List.of("blocks","chats","commands","containers","items","sessions");
    private static final List<String> TABLES=List.of("users","usernames","levels","materials","entities","blocks","chats","commands","containers","items","sessions");
    private static final int ROWS=2048;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static boolean started;
    private static int checks;

    public static void tick(ServerTickEvent.Post event) {
        if(started||event.getServer().getTickCount()<20) return;
        started=true;
        var server=event.getServer(); var directory=server.getServerDirectory();
        var phase=System.getenv("CITIZENS_LOGGING_AUDIT_PHASE");
        boolean recover="indexes-recover".equals(phase), enabled=!"indexes-disabled".equals(phase);
        try(var observer=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("database.db").toAbsolutePath())) {
            check(GriefLoggerConfig.useIndexes.get()==enabled,"configured_index_creation_mode_is_respected");
            check(GriefLogger.getDatabase().flushPendingWrites(),"startup_producers_commit_before_schema_and_payload_checks");
            var original=read(directory.resolve("logging-index-original.json"));
            JsonObject state;
            if(recover) {
                state=read(directory.resolve("logging-index-state.json"));
                check(snapshot(observer).equals(state.get("tables")),"restart_preserves_every_original_and_workload_row_before_lookup");
            } else {
                check(snapshot(observer).equals(original),"provider_upgrade_preserves_every_original_stopped_row");
                state=new JsonObject();
                String actor=UUID.randomUUID().toString(); state.addProperty("actor",actor); state.addProperty("rowsPerTable",ROWS);
                seed(observer,actor);
                check(GriefLogger.getDatabase().flushPendingWrites(),"real_provider_commits_complete_queued_workload");
                state.add("tables",snapshot(observer)); state.addProperty("user",scalar(observer,"SELECT id FROM users WHERE uuid='"+actor+"'"));
                state.add("initialIndexes",indexes(observer));
                state.add("initialQueries",queries(observer,state.get("user").getAsInt()));
                write(directory.resolve("logging-index-state.json"),state);
            }
            var indices=indexes(observer); var queries=queries(observer,state.get("user").getAsInt());
            for(String table:HISTORY) {
                check(indices.getAsJsonArray(table).size()==(enabled?1:0),"every_history_table_owns_coordinate_index_"+table);
                var query=queries.getAsJsonObject(table); var plan=query.getAsJsonArray("plan");
                boolean search=false,scan=false;
                for(var step:plan) {var detail=step.getAsString(); search|=detail.contains("SEARCH "+table+" USING INDEX ");scan|=detail.startsWith("SCAN "+table);}
                check(enabled?search&&!scan:scan&&!search,"coordinate_lookup_uses_configured_plan_"+table);
                var expected=new JsonArray();
                var values=state.getAsJsonObject("tables").getAsJsonArray(table).asList().stream().map(JsonElement::getAsJsonArray)
                        .filter(row->row.get(2).getAsInt()==state.get("user").getAsInt()&&row.get(4).getAsInt()==7&&row.get(5).getAsInt()==-60&&row.get(6).getAsInt()==3)
                        .sorted((a,b)->Long.compare(b.get(1).getAsLong(),a.get(1).getAsLong())).toList();
                values.forEach(expected::add);
                check(expected.size()==2&&query.get("rows").equals(expected),"indexed_lookup_preserves_exact_ordered_payload_"+table);
            }
            var before=snapshot(observer);
            if(enabled) {
                Services.BLOCK.createIndexes(); Services.CHAT.createIndexes(); Services.COMMAND.createIndexes();
                Services.CONTAINER.createIndexes(); Services.ITEM.createIndexes(); Services.SESSION.createIndexes();
                check(GriefLogger.getDatabase().completeInitialization(),"repeated_native_index_initialization_commits");
                check(indexes(observer).equals(indices),"repeated_initialization_does_not_duplicate_coordinate_indexes");
            }
            check(snapshot(observer).equals(before),"lookup_and_index_initialization_change_no_retained_rows");
            for(String table:TABLES) {
                var prior=original.getAsJsonArray(table).asList(); var current=before.getAsJsonArray(table).asList();
                if(current.size()<prior.size()||!current.subList(0,prior.size()).equals(prior)) throw new AssertionError("Original row changed: "+table);
            }
            check(true,"every_preupgrade_history_dictionary_and_identity_row_remains_exact");
            var result=new JsonObject(); result.addProperty("phase",phase);result.addProperty("checks",checks);
            result.add("indexes",indices);result.add("queries",queries);
            write(directory.resolve(recover?"logging-index-recovery.json":"logging-index-result.json"),result);
            LOG.info("[LOGGINGINDEX] COMPLETE {} checks phase={}",checks,phase);
            LOG.info("[LOGGINGAUDIT] COMPLETE index acceptance");
        } catch(Throwable failure) {LOG.error("[LOGGINGAUDIT] FAILED index acceptance",failure);}
        server.halt(false);
    }

    private static void seed(Connection observer,String actor) throws Exception {
        var database=GriefLogger.getDatabase();
        var user=database.prepareQueuedStatement("INSERT INTO users(uuid,name) VALUES(?,?)");
        user.setString(1,actor);user.setString(2,"IndexLoad");database.queue.add(user);
        if(!database.flushPendingWrites())throw new AssertionError("Could not commit workload identity");
        int userId=scalar(observer,"SELECT id FROM users WHERE uuid='"+actor+"'");
        int level=scalar(observer,"SELECT id FROM levels WHERE name='minecraft:overworld'");
        int material=scalar(observer,"SELECT id FROM materials WHERE name='paper'");
        byte[] components;
        try(var query=observer.createStatement();var rows=query.executeQuery("SELECT data FROM containers ORDER BY rowid LIMIT 1")) {if(!rows.next())throw new AssertionError("Missing original native components");components=rows.getBytes(1);}
        long time=System.currentTimeMillis();
        for(String table:HISTORY) {
            String extra=switch(table){case "blocks"->",type,action";case "chats"->",message";case "commands"->",command";case "containers","items"->",type,data,amount,action";default->",action";};
            String suffix=switch(table){case "blocks"->",?,?";case "containers","items"->",?,?,?,?";default->",?";};
            for(int i=0;i<ROWS;i++) {
                var row=database.prepareQueuedStatement("INSERT INTO "+table+"(time,user,level,x,y,z"+extra+") VALUES(?,?,?,?,?,?"+suffix+")");
                row.setLong(1,time+i);row.setInt(2,userId);row.setInt(3,level);
                row.setInt(4,i==0||i==ROWS-1?7:100+i);row.setInt(5,-60);row.setInt(6,3);
                switch(table) {
                    case "blocks"->{row.setInt(7,material);row.setInt(8,0);}
                    case "chats","commands"->row.setString(7,"Index payload '\" "+i);
                    case "containers","items"->{row.setInt(7,material);row.setBytes(8,components);row.setInt(9,7);row.setInt(10,0);}
                    default->row.setInt(7,i%2);
                }
                database.queue.add(row);
            }
        }
    }
    private static int scalar(Connection db,String sql) throws SQLException {try(var query=db.createStatement();var rows=query.executeQuery(sql)){if(!rows.next())throw new SQLException("Missing scalar");return rows.getInt(1);}}
    private static String projection(String table) {return table.equals("blocks")?"rowid,time,user,level,x,y,z,type,action":"rowid,*";}
    private static JsonObject snapshot(Connection db) throws SQLException {var value=new JsonObject();for(String table:TABLES)try(var query=db.createStatement();var rows=query.executeQuery("SELECT "+projection(table)+" FROM "+table+" ORDER BY rowid")){value.add(table,rows(rows));}return value;}
    private static JsonArray rows(ResultSet rows) throws SQLException {
        var values=new JsonArray();
        while(rows.next()) {var row=new JsonArray();for(int i=1;i<=rows.getMetaData().getColumnCount();i++){var value=rows.getObject(i);if(value instanceof byte[] bytes){var blob=new JsonObject();blob.addProperty("blob",HexFormat.of().formatHex(bytes));row.add(blob);}else row.add(new Gson().toJsonTree(value));}values.add(row);}return values;
    }
    private static JsonObject indexes(Connection db) throws SQLException {
        var value=new JsonObject();
        for(String table:HISTORY) {
            var names=new JsonArray();
            try(var query=db.createStatement();var found=query.executeQuery("PRAGMA index_list('"+table+"')")) {
                while(found.next()) {String name=found.getString("name");var columns=new ArrayList<String>();try(var inspect=db.createStatement();var info=inspect.executeQuery("PRAGMA index_info('"+name+"')")){while(info.next())columns.add(info.getString("name"));}if(columns.equals(List.of("x","y","z")))names.add(name);}
            }
            value.add(table,names);
        }
        return value;
    }
    private static JsonObject queries(Connection db,int user) throws SQLException {
        var value=new JsonObject();
        for(String table:HISTORY) {
            String sql="SELECT "+projection(table)+" FROM "+table+" WHERE user=? AND x=? AND y=? AND z=? ORDER BY time DESC";
            var item=new JsonObject();item.addProperty("sql",sql);var plan=new JsonArray();
            try(var query=db.prepareStatement("EXPLAIN QUERY PLAN "+sql)){bind(query,user);try(var rows=query.executeQuery()){while(rows.next())plan.add(rows.getString("detail"));}}
            item.add("plan",plan);
            try(var query=db.prepareStatement(sql)){bind(query,user);try(var rows=query.executeQuery()){item.add("rows",rows(rows));}}
            value.add(table,item);
        }
        return value;
    }
    private static void bind(PreparedStatement query,int user) throws SQLException {query.setInt(1,user);query.setInt(2,7);query.setInt(3,-60);query.setInt(4,3);}
    private static JsonObject read(Path path) throws Exception {return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    private static void write(Path path,JsonObject value) throws Exception {try(var file=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){var bytes=StandardCharsets.UTF_8.encode(value.toString());while(bytes.hasRemaining())file.write(bytes);file.force(true);}}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;LOG.info("[LOGGINGINDEX] PASS {}",name);}
}
