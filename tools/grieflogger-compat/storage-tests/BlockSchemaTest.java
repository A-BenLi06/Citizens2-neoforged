import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.daqem.grieflogger.database.Database;
import com.daqem.grieflogger.database.schema.BlockSchema;

/** Actual provider migrations and actual drivers; no schema implementation is substituted. */
public final class BlockSchemaTest {
    private static boolean mysql;
    private static Properties settings;
    private static Path root;
    private static int checks;
    private record Fixture(Connection connection,Connection observer,Database database,String name) implements AutoCloseable {
        public void close()throws SQLException{try{connection.rollback();}finally{connection.close();observer.close();}}
    }
    private static void sql(Connection connection,String text)throws SQLException{try(var query=connection.createStatement()){query.execute(text);}}
    private static List<List<String>> rows(Connection connection,String text)throws SQLException {
        var result=new ArrayList<List<String>>();try(var query=connection.createStatement();var rows=query.executeQuery(text)){while(rows.next()){var row=new ArrayList<String>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.add(rows.getString(i));result.add(row);}}return result;
    }
    private static long count(Connection connection,String table)throws SQLException{return Long.parseLong(rows(connection,"SELECT COUNT(*) FROM "+table).getFirst().getFirst());}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;System.out.println("[BLOCKSCHEMA] PASS "+message);}
    private static Connection connect(String name)throws SQLException {
        if(!mysql){var connection=DriverManager.getConnection("jdbc:sqlite:"+root.resolve(name+".db"));sql(connection,"PRAGMA foreign_keys=ON");return connection;}
        return DriverManager.getConnection(settings.getProperty("urlPrefix")+name,settings.getProperty("user"),settings.getProperty("password"));
    }
    private static Fixture fixture(String label,boolean legacy,boolean orphan,boolean custom)throws Exception {
        String name=(mysql?settings.getProperty("schemaDatabasePrefix","loggingaudit_schema_"):"loggingaudit_schema_")+label;
        var connection=connect(name);var observer=connect(name);
        for(String table:List.of("users","levels","materials","entities"))sql(connection,"CREATE TABLE "+table+"(id "+(mysql?"int":"integer")+" PRIMARY KEY,name VARCHAR(64) NOT NULL)");
        sql(connection,"CREATE TABLE audit(value int NOT NULL)");
        sql(connection,"INSERT INTO users VALUES(1,'actor')");sql(connection,"INSERT INTO levels VALUES(1,'minecraft:overworld')");
        if(legacy) {
            String integer=mysql?"int":"integer";
            sql(connection,"CREATE TABLE blocks(time "+(mysql?"bigint":integer)+" NOT NULL,user "+integer+" NOT NULL,level "+integer+" NOT NULL,x "+integer+" NOT NULL,y "+integer+" NOT NULL,z "+integer+" NOT NULL,type "+integer+" NOT NULL,action "+integer+" NOT NULL,FOREIGN KEY(user) REFERENCES users(id),FOREIGN KEY(level) REFERENCES levels(id),"+(mysql?"CONSTRAINT `legacy type registry` ":"")+"FOREIGN KEY(type) REFERENCES materials(id))"+(mysql?" ENGINE=InnoDB COMMENT='original table semantics'":""));
            if(!mysql)sql(connection,"PRAGMA foreign_keys=OFF");
            sql(connection,"INSERT INTO materials VALUES(19,'stone')");sql(connection,"INSERT INTO entities VALUES(31,'pig')");
            if(mysql)sql(connection,"INSERT INTO materials VALUES(31,'original material with overlapping ID')");
            if(orphan)sql(connection,"INSERT INTO materials VALUES(93,'original material without entity counterpart')");
            String fields=(mysql?"":"rowid,")+"time,user,level,x,y,z,type,action";
            sql(connection,"INSERT INTO blocks("+fields+") VALUES("+(mysql?"":"41,")+"100,1,1,7,-60,3,19,1)");
            sql(connection,"INSERT INTO blocks("+fields+") VALUES("+(mysql?"":"99,")+"101,1,1,4,-60,6,"+(orphan?93:31)+",3)");
            sql(connection,"CREATE INDEX original_coordinates ON blocks(x,y,z)");
            if(mysql)try(var bootstrap=DriverManager.getConnection(settings.getProperty("urlPrefix")+name,settings.getProperty("schemaAdminUser"),settings.getProperty("schemaAdminPassword"))) {
                // Existing-trigger setup needs the fixture administrator under default binary logging.
                // Migration and all subsequent writes still use the ordinary schema-scoped account.
                sql(bootstrap,"CREATE TRIGGER original_insert AFTER INSERT ON blocks FOR EACH ROW INSERT INTO audit VALUES(1)");
            } else sql(connection,"CREATE TRIGGER original_insert AFTER INSERT ON blocks BEGIN INSERT INTO audit VALUES(1); END");
            if(custom)sql(connection,"ALTER TABLE blocks ADD COLUMN custom_data TEXT");
            if(!mysql)sql(connection,"PRAGMA foreign_keys=ON");
        }
        connection.setAutoCommit(false);
        var database=new Database();var field=Database.class.getDeclaredField("connection");field.setAccessible(true);field.set(database,connection);
        var statement=Database.class.getDeclaredField("statement");statement.setAccessible(true);statement.set(database,connection.createStatement());
        return new Fixture(connection,observer,database,name);
    }
    private static List<List<String>> history(Connection connection)throws SQLException {
        return rows(connection,"SELECT "+(mysql?"":"rowid,")+"time,user,level,x,y,z,type,action FROM blocks ORDER BY time");
    }
    private static List<List<String>> schema(Connection connection)throws SQLException {
        return rows(connection,mysql?"SHOW CREATE TABLE blocks":"SELECT sql FROM sqlite_master WHERE name='blocks' OR tbl_name='blocks' ORDER BY type,name");
    }
    private static void reject(Fixture fixture,String statement,String label)throws Exception {
        try{sql(fixture.connection,statement);throw new AssertionError("Invalid registry write accepted: "+label);}
        catch(SQLException expected){check(mysql?expected.getErrorCode()==1452||expected.getErrorCode()==1451:expected.getMessage().contains("FOREIGN KEY constraint failed"),label+"_actual_foreign_key_rejection");}
        fixture.connection.rollback();
    }
    private static void fresh()throws Exception {
        try(var fixture=fixture("fresh",false,false,false)) {
            BlockSchema.initialize(fixture.database,mysql);check(fixture.database.completeInitialization(),"fresh_actual_provider_schema_ready");
            sql(fixture.connection,"INSERT INTO entities VALUES(31,'pig')");
            sql(fixture.connection,"INSERT INTO blocks(time,user,level,x,y,z,type,action) VALUES(100,1,1,4,-60,6,31,3)");fixture.connection.commit();
            check(count(fixture.observer,"materials")==0&&count(fixture.observer,"blocks")==1,"entity_history_commits_with_empty_material_dictionary");
            check(rows(fixture.observer,"SELECT type,action,material_type,entity_type FROM blocks").equals(List.of(Arrays.asList("31","3",null,"31"))),"entity_history_projects_only_real_entity_reference");
            reject(fixture,"INSERT INTO blocks(time,user,level,x,y,z,type,action) VALUES(101,1,1,4,-60,6,31,1)","entity_id_cannot_impersonate_missing_material");
            sql(fixture.connection,"INSERT INTO materials VALUES(19,'stone')");fixture.connection.commit();
            reject(fixture,"INSERT INTO blocks(time,user,level,x,y,z,type,action) VALUES(102,1,1,4,-60,6,19,3)","material_id_cannot_impersonate_missing_entity");
            sql(fixture.connection,"INSERT INTO blocks(time,user,level,x,y,z,type,action) VALUES(103,1,1,7,-60,3,19,1)");fixture.connection.commit();
            reject(fixture,"DELETE FROM entities WHERE id=31","used_entity_cannot_be_deleted");
            reject(fixture,"DELETE FROM materials WHERE id=19","used_material_cannot_be_deleted");
            reject(fixture,"UPDATE blocks SET action=1 WHERE type=31","action_change_cannot_reassign_wrong_registry");
            var prior=history(fixture.observer);var schema=schema(fixture.observer);BlockSchema.initialize(fixture.database,mysql);
            check(fixture.database.completeInitialization()&&history(fixture.observer).equals(prior)&&schema(fixture.observer).equals(schema),"repeated_initialization_preserves_exact_schema_and_rows");
        }
    }
    private static void legacy()throws Exception {
        try(var fixture=fixture("legacy",true,false,false)) {
            var before=history(fixture.observer);var materials=rows(fixture.observer,"SELECT * FROM materials ORDER BY id");
            BlockSchema.initialize(fixture.database,mysql);check(fixture.database.completeInitialization(),"legacy_actual_provider_migration_ready");
            check(history(fixture.observer).equals(before)&&rows(fixture.observer,"SELECT * FROM materials ORDER BY id").equals(materials),"legacy_migration_preserves_every_original_history_value_rowid_and_dictionary");
            check(count(fixture.observer,"audit")==0,"legacy_migration_does_not_fire_original_insert_trigger");
            check(rows(fixture.observer,"SELECT material_type,entity_type FROM blocks ORDER BY time").equals(List.of(Arrays.asList("19",null),Arrays.asList(null,"31"))),"legacy_migration_projects_correct_actual_registries");
            String indexSql=mysql?"SELECT INDEX_NAME FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='blocks' AND INDEX_NAME='original_coordinates' GROUP BY INDEX_NAME":"SELECT name FROM sqlite_master WHERE type='index' AND name='original_coordinates'";
            check(rows(fixture.observer,indexSql).equals(List.of(List.of("original_coordinates"))),"legacy_migration_preserves_custom_coordinate_index");
            sql(fixture.connection,"INSERT INTO blocks(time,user,level,x,y,z,type,action) VALUES(102,1,1,7,-60,3,19,1)");fixture.connection.commit();
            check(count(fixture.observer,"audit")==1,"legacy_migration_preserves_original_trigger_for_new_native_rows");
            reject(fixture,"DELETE FROM entities WHERE id=31","migrated_entity_has_real_restrictive_foreign_key");
            if(mysql)check(schema(fixture.observer).getFirst().get(1).contains("original table semantics"),"mysql_migration_retains_original_table_comment");
            else check(!rows(fixture.observer,"SELECT rowid FROM blocks WHERE rowid IN (41,99) ORDER BY rowid").isEmpty(),"sqlite_migration_retains_original_sparse_rowids");
            var after=history(fixture.observer);BlockSchema.initialize(fixture.database,mysql);
            check(fixture.database.completeInitialization()&&history(fixture.observer).equals(after),"migrated_schema_is_idempotent");
        }
    }
    private static void failed(boolean orphan)throws Exception {
        String label=orphan?"orphan":"custom";
        try(var fixture=fixture(label,true,orphan,!orphan)) {
            var original=history(fixture.observer);var schema=schema(fixture.observer);
            BlockSchema.initialize(fixture.database,mysql);
            check(!fixture.database.completeInitialization(),label+"_migration_cannot_report_false_readiness");
            check(history(fixture.observer).equals(original)&&schema(fixture.observer).equals(schema),label+"_failed_migration_preserves_exact_original_schema_rows_and_indexes");
            fixture.database.execute("CREATE INDEX after_failed_schema ON blocks(x)",true);
            check(schema(fixture.connection).equals(schema),label+"_failed_schema_prevents_later_optional_index_ddl");
            try{fixture.database.read("SELECT * FROM blocks",q->{throw new AssertionError("Fenced schema reached query");});throw new AssertionError("Fenced schema allowed read");}
            catch(SQLException expected){check(true,label+"_failed_migration_fences_public_reads");}
            if(!mysql)check(rows(fixture.observer,"SELECT name FROM sqlite_master WHERE name='blocks_grieflogger_migration'").isEmpty(),label+"_failed_sqlite_rebuild_leaves_no_partial_table");
        }
    }
    public static void main(String[] args)throws Exception {
        mysql=args[0].equals("mysql");
        if(mysql){settings=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[1]))){settings.load(reader);}Class.forName("com.mysql.cj.jdbc.Driver");}
        else{root=Path.of(args[1]);Files.createDirectories(root);Class.forName("org.sqlite.JDBC");}
        System.out.println("[BLOCKSCHEMA] PROVIDER "+BlockSchema.class.getProtectionDomain().getCodeSource().getLocation());
        fresh();legacy();failed(true);failed(false);
        System.out.println("[BLOCKSCHEMA] COMPLETE "+checks+" checks backend="+(mysql?"mysql":"sqlite"));
    }
}
