package net.yuuniverse.interactions;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import com.daqem.grieflogger.GriefLogger;
import com.daqem.grieflogger.command.filter.*;
import com.daqem.grieflogger.database.queue.QueuedWrite;
import com.daqem.grieflogger.database.repository.*;
import com.daqem.grieflogger.model.history.*;
import com.google.gson.*;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real repository APIs and SQLite, with a deterministic pause after native SQL writes. */
public final class LoggingReadRuntimeAudit {
    private static final List<String> TABLES=List.of("users","usernames","levels","materials","entities","blocks","chats","commands","containers","items","sessions");
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("interactions");
    private static final Gson GSON=new Gson();
    private static boolean started;
    private static int checks;
    private static final int X=29,Y=-60,Z=29;
    public static void tick(ServerTickEvent.Post event) {
        if(started||event.getServer().getTickCount()<20)return;
        started=true;
        var server=event.getServer();var directory=server.getServerDirectory();
        boolean recover="reads-recover".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"));
        try(var observer=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("database.db").toAbsolutePath())) {
            check(GriefLogger.getDatabase().flushPendingWrites(),"startup_producers_committed_before_lookup_checks");
            var calls=lookups(server);
            JsonObject state;
            if(recover) {
                state=read(directory.resolve("logging-read-state.json"));
                check(snapshot(observer).equals(state.get("tables")),"fresh_process_preserves_all_exact_dictionary_and_history_rows");
                var found=new JsonObject();
                for(var call:calls.entrySet()) {var result=call.getValue().call();check(result.equals(state.getAsJsonObject("lookups").get(call.getKey())),"restart_exact_public_repository_"+call.getKey());found.add(call.getKey(),result);}
                state=new JsonObject();state.add("lookups",found);state.addProperty("checks",checks);
                write(directory.resolve("logging-read-recovery.json"),state);
            } else {
                var original=snapshot(observer);var baseline=new JsonObject();
                check(original.equals(read(directory.resolve("logging-index-original.json"))),"provider_startup_preserves_every_original_stopped_row");
                for(var call:calls.entrySet())baseline.add(call.getKey(),call.getValue().call());
                for(var call:baseline.entrySet())if(!call.getKey().equals("usernames"))check(call.getValue().getAsJsonArray().isEmpty(),"fresh_lookup_coordinates_have_no_history_"+call.getKey());
                byte[] data;
                try(var query=observer.createStatement();var rows=query.executeQuery("SELECT data FROM containers ORDER BY rowid LIMIT 1")){if(!rows.next())throw new AssertionError("Missing native component fixture");data=rows.getBytes(1);}
                JsonObject committed=null;
                for(boolean commit:new boolean[]{false,true}) {
                    String actor=UUID.randomUUID().toString();long time=System.currentTimeMillis();
                    var expected=expected(server,baseline,actor,time,data,commit);
                    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reading=new CountDownLatch(calls.size());
                    var pool=Executors.newFixedThreadPool(calls.size()+1);
                    try {
                        var writes=seed(observer,actor,time,data);
                        writes.add(connection->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new SQLException("Native read barrier timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new SQLException(failure);}if(!commit)throw new SQLException("Expected fixture-owned lookup rollback");});
                        var writer=pool.submit(()->GriefLogger.getDatabase().executeWrites(writes));
                        check(entered.await(3,TimeUnit.SECONDS),"actual_uncommitted_history_writes_reach_barrier_"+commit);
                        var readers=new LinkedHashMap<String,Future<JsonElement>>();
                        for(var call:calls.entrySet())readers.put(call.getKey(),pool.submit(()->{reading.countDown();return call.getValue().call();}));
                        check(reading.await(3,TimeUnit.SECONDS),"all_nine_public_lookup_workers_started_"+commit);
                        for(var reader:readers.entrySet()) {
                            try {reader.getValue().get(80,TimeUnit.MILLISECONDS);throw new AssertionError("Lookup returned during active write: "+reader.getKey());}
                            catch(TimeoutException correct){check(true,"public_lookup_waits_for_outcome_"+reader.getKey()+"_"+commit);}
                        }
                        check(snapshot(observer).equals(original),"independent_sqlite_connection_sees_only_original_committed_rows_"+commit);
                        release.countDown();check(writer.get(3,TimeUnit.SECONDS)==commit,"native_write_reaches_expected_outcome_"+commit);
                        var actual=new JsonObject();
                        for(var reader:readers.entrySet()) {
                            var found=reader.getValue().get(3,TimeUnit.SECONDS);
                            if(!found.equals(expected.get(reader.getKey())))throw new AssertionError("Wrong lookup "+reader.getKey()+" expected="+expected.get(reader.getKey())+" actual="+found);
                            check(true,"exact_committed_public_lookup_"+reader.getKey()+"_"+commit);actual.add(reader.getKey(),found);
                        }
                        if(commit)committed=actual;else check(snapshot(observer).equals(original),"failed_transaction_preserves_every_original_row_and_component");
                    } finally {release.countDown();pool.shutdownNow();if(!pool.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Native lookup workers did not stop");}
                }
                var after=snapshot(observer);
                for(String table:TABLES){var prior=original.getAsJsonArray(table).asList();var now=after.getAsJsonArray(table).asList();if(now.size()<prior.size()||!now.subList(0,prior.size()).equals(prior))throw new AssertionError("Original changed: "+table);}
                check(true,"successful_transaction_and_all_lookups_preserve_every_original_row");
                check(after.getAsJsonArray("users").size()==original.getAsJsonArray("users").size()+1,"only_committed_identity_survives_rollback_and_commit");
                for(String table:List.of("blocks","containers","items","sessions"))check(after.getAsJsonArray(table).size()==original.getAsJsonArray(table).size()+(table.equals("blocks")?3:2),"only_exact_committed_history_rows_survive_"+table);
                state=new JsonObject();state.add("tables",after);state.add("lookups",committed);state.addProperty("checks",checks);
                write(directory.resolve("logging-read-state.json"),state);
            }
            LOG.info("[LOGGINGREAD] COMPLETE {} checks phase={}",checks,recover?"reads-recover":"reads");
            LOG.info("[LOGGINGAUDIT] COMPLETE read isolation acceptance");
        } catch(Throwable failure){LOG.error("[LOGGINGAUDIT] FAILED read isolation acceptance",failure);}
        server.halt(false);
    }
    private static LinkedHashMap<String,Callable<JsonElement>> lookups(MinecraftServer server) {
        var db=GriefLogger.getDatabase();var level=server.overworld();String name=level.dimension().location().toString();
        var filters=new FilterList(List.of(),server.createCommandSourceStack());var radius=new RadiusFilter(0);radius.setPosition(new com.daqem.grieflogger.model.BlockPosition(X,Y,Z));filters.setRadiusFilter(radius);
        var block=new BlockRepository(db);var container=new ContainerRepository(db);var item=new ItemRepository(db);var session=new SessionRepository(db);var user=new UserRepository(db);
        var calls=new LinkedHashMap<String,Callable<JsonElement>>();
        calls.put("blocks",()->describe(block.getBlockHistory(name,X,Y,Z)));
        calls.put("interactions",()->describe(block.getInteractionHistory(name,X,Y,Z)));
        calls.put("filteredBlocks",()->describe(block.getFilteredBlockHistory(name,filters)));
        calls.put("containers",()->describe(container.getHistory(level,X,Y,Z)));
        calls.put("containerRange",()->describe(container.getHistory(level,X,Y,Z,X,Y,Z)));
        calls.put("filteredContainers",()->describe(container.getFilteredContainerHistory(level,filters)));
        calls.put("items",()->describe(item.getFilteredItemHistory(level,filters)));
        calls.put("sessions",()->describe(session.getFilteredSessionHistory(name,filters)));
        calls.put("usernames",()->GSON.toJsonTree(user.getAllUsernames()));return calls;
    }
    private static JsonArray describe(List<? extends IHistory> histories) {
        var result=new JsonArray();
        for(var history:histories){var row=new JsonObject();row.addProperty("time",history.getTime().time());row.addProperty("name",history.getUser().getName());row.addProperty("uuid",history.getUser().getUuid().orElseThrow().toString());row.add("position",GSON.toJsonTree(history.getPosition()));row.addProperty("action",history.getAction().getId());
            if(history instanceof ItemHistory item){var stack=item.getItemStack();row.addProperty("paper",stack.getItem()==Items.PAPER);row.addProperty("amount",stack.getCount());row.addProperty("patch",stack.getTag().toString());}result.add(row);}
        return result;
    }
    private static JsonObject expected(MinecraftServer server,JsonObject baseline,String actor,long time,byte[] data,boolean commit) throws Exception {
        var result=baseline.deepCopy();if(!commit)return result;
        var buffer=new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data),server.registryAccess());
        DataComponentPatch patch;try{patch=DataComponentPatch.STREAM_CODEC.decode(buffer);}finally{buffer.release();}
        for(var key:baseline.keySet()) {
            if(key.equals("usernames"))continue;
            int[] actions=key.equals("interactions")?new int[]{2}:key.equals("filteredBlocks")?new int[]{2,1,0}:new int[]{1,0};var rows=new JsonArray();
            for(int action:actions){var row=new JsonObject();row.addProperty("time",time+action);row.addProperty("name","ReadAudit");row.addProperty("uuid",actor);row.add("position",GSON.toJsonTree(new com.daqem.grieflogger.model.BlockPosition(X,Y,Z)));row.addProperty("action",action);
                if(key.toLowerCase(Locale.ROOT).contains("container")||key.equals("items")){row.addProperty("paper",true);row.addProperty("amount",7);row.addProperty("patch",patch.toString());}rows.add(row);}result.add(key,rows);
        }
        // IDs are allocated by the actual SQLite schema, including all retained identities.
        try(var observer=DriverManager.getConnection("jdbc:sqlite:"+server.getServerDirectory().resolve("database.db").toAbsolutePath())) {
            result.getAsJsonObject("usernames").addProperty(Integer.toString(scalar(observer,"SELECT COALESCE(MAX(id),0)+1 FROM users")),"ReadAudit");
        }
        return result;
    }
    private static List<QueuedWrite> seed(Connection observer,String actor,long time,byte[] data) throws Exception {
        var db=GriefLogger.getDatabase();var writes=new ArrayList<QueuedWrite>();
        var user=db.prepareQueuedStatement("INSERT INTO users(uuid,name) VALUES(?,?)");user.setString(1,actor);user.setString(2,"ReadAudit");writes.add(user.freeze(false));
        int level=scalar(observer,"SELECT id FROM levels WHERE name='minecraft:overworld'"),material=scalar(observer,"SELECT id FROM materials WHERE name='paper'");
        for(String table:List.of("blocks","containers","items","sessions"))for(int action=0;action<(table.equals("blocks")?3:2);action++) {
            String extra=table.equals("blocks")?",type,action":table.equals("sessions")?",action":",type,data,amount,action";
            String suffix=table.equals("blocks")?",?,?":table.equals("sessions")?",?":",?,?,?,?";
            var row=db.prepareQueuedStatement("INSERT INTO "+table+"(time,user,level,x,y,z"+extra+") VALUES(?,(SELECT id FROM users WHERE uuid=?),?,?,?,?"+suffix+")");
            row.setLong(1,time+action);row.setString(2,actor);row.setInt(3,level);row.setInt(4,X);row.setInt(5,Y);row.setInt(6,Z);
            if(table.equals("blocks")){row.setInt(7,material);row.setInt(8,action);}else if(table.equals("sessions"))row.setInt(7,action);else{row.setInt(7,material);row.setBytes(8,data);row.setInt(9,7);row.setInt(10,action);}writes.add(row.freeze(false));
        }
        return writes;
    }
    private static int scalar(Connection db,String sql)throws SQLException{try(var query=db.createStatement();var rows=query.executeQuery(sql)){if(!rows.next())throw new SQLException("Missing scalar");return rows.getInt(1);}}
    private static JsonObject snapshot(Connection db)throws SQLException {
        var result=new JsonObject();for(String table:TABLES)try(var query=db.createStatement();var rows=query.executeQuery("SELECT "+(table.equals("blocks")?"rowid,time,user,level,x,y,z,type,action":"rowid,*")+" FROM "+table+" ORDER BY rowid")){var values=new JsonArray();while(rows.next()){var row=new JsonArray();for(int i=1;i<=rows.getMetaData().getColumnCount();i++){var value=rows.getObject(i);if(value instanceof byte[] bytes){var blob=new JsonObject();blob.addProperty("blob",HexFormat.of().formatHex(bytes));row.add(blob);}else row.add(GSON.toJsonTree(value));}values.add(row);}result.add(table,values);}return result;
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
    private static void write(Path path,JsonObject value)throws Exception{Files.writeString(path,value.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;LOG.info("[LOGGINGREAD] PASS {}",name);}
}
