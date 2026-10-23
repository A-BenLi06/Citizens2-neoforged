import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import com.daqem.grieflogger.database.Database;
import com.daqem.grieflogger.database.queue.*;
import com.daqem.grieflogger.database.repository.UserRepository;

/** Actual MySQL wire protocol and packaged provider; fixture credentials stay outside logs. */
public final class MysqlStorageRecoveryTest {
    private static Properties settings;
    private static int checks;
    private record Fixture(Connection connection,Connection observer,Database database,String table) implements AutoCloseable {
        public void close()throws SQLException{try{connection.rollback();}finally{connection.close();observer.close();}}
    }
    private static Fixture fixture(String name)throws Exception {
        var connection=DriverManager.getConnection(settings.getProperty("url"),settings.getProperty("user"),settings.getProperty("password"));
        var observer=DriverManager.getConnection(settings.getProperty("url"),settings.getProperty("user"),settings.getProperty("password"));
        sql(connection,"SET SESSION innodb_lock_wait_timeout=1");connection.setAutoCommit(false);
        var database=new Database();var field=Database.class.getDeclaredField("connection");field.setAccessible(true);field.set(database,connection);
        String table="observations_"+name;
        database.createTable("CREATE TABLE "+table+"(id INT PRIMARY KEY,value TEXT,data BLOB) ENGINE=InnoDB");
        check(database.completeInitialization(),name+"_real_mysql_schema_ready");
        return new Fixture(connection,observer,database,table);
    }
    private static void sql(Connection db,String text)throws SQLException{try(var query=db.createStatement()){query.execute(text);}}
    private static QueuedStatement write(Fixture f,int id,String value,byte[] data)throws SQLException {
        var query=f.database.prepareQueuedStatement("INSERT INTO "+f.table+" VALUES(?,?,?)");query.setInt(1,id);query.setString(2,value);query.setBytes(3,data);return query;
    }
    private static int count(Fixture f)throws SQLException{try(var query=f.observer.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM "+f.table)){rows.next();return rows.getInt(1);}}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("[MYSQLSTORAGE] PASS "+name);}
    private static void lockRetry(boolean batch)throws Exception {
        String label=batch?"batch_lock":"ordinary_lock";
        try(var f=fixture(label)) {
            sql(f.observer,"INSERT INTO "+f.table+" VALUES(1,'original',NULL)");
            var queue=batch?f.database.batchQueue:f.database.queue;
            var bytes=new byte[]{1,2,3};var prefix=write(f,2,"retained 汉🙂",bytes);if(batch)prefix.addBatch();queue.add(prefix);bytes[0]=9;
            var update=f.database.prepareQueuedStatement("UPDATE "+f.table+" SET value=? WHERE id=1");update.setString(1,"updated");if(batch)update.addBatch();queue.add(update);
            f.observer.setAutoCommit(false);sql(f.observer,"UPDATE "+f.table+" SET value='locked' WHERE id=1");
            queue.execute();
            var failure=com.daqem.grieflogger.GriefLogger.lastFailure;
            check(failure instanceof SQLException error&&error.getErrorCode()==1205,label+"_actual_innodb_row_lock_timeout");
            f.observer.rollback();f.observer.setAutoCommit(true);
            check(count(f)==1&&queue.pendingCount()==2,label+"_rollback_retains_entire_prefix_without_committed_leak");
            queue.execute();check(count(f)==2&&queue.pendingCount()==0,label+"_unlock_retries_original_writes_once");
            try(var query=f.observer.createStatement();var rows=query.executeQuery("SELECT id,value,data FROM "+f.table+" ORDER BY id")) {
                rows.next();check(rows.getInt(1)==1&&rows.getString(2).equals("updated"),label+"_exact_original_update");
                rows.next();check(rows.getInt(1)==2&&rows.getString(2).equals("retained 汉🙂")&&Arrays.equals(rows.getBytes(3),new byte[]{1,2,3}),label+"_exact_owned_utf8_and_binary_parameters");
            }
            queue.execute();check(count(f)==2,label+"_repeated_flush_does_not_duplicate");
        }
    }
    private static void batchConstraint()throws Exception {
        try(var f=fixture("constraint")) {
            sql(f.observer,"INSERT INTO "+f.table+" VALUES(2,'existing',NULL)");
            var rows=write(f,1,null,new byte[]{4});rows.addBatch();rows.setInt(1,2);rows.setString(2,"second");rows.setBytes(3,new byte[]{5});rows.addBatch();f.database.batchQueue.add(rows);
            f.database.batchQueue.execute();
            check(com.daqem.grieflogger.GriefLogger.lastFailure instanceof SQLException error&&error.getErrorCode()==1062,"constraint_actual_mysql_duplicate_key");
            check(count(f)==1&&f.database.batchQueue.pendingCount()==1,"constraint_failed_batch_rolls_back_prefix_and_retains_owned_parameters");
            sql(f.observer,"DELETE FROM "+f.table+" WHERE id=2");f.database.batchQueue.execute();
            check(count(f)==2&&f.database.batchQueue.pendingCount()==0,"constraint_retry_recreates_driver_batch_and_commits_once");
            try(var query=f.observer.createStatement();var result=query.executeQuery("SELECT value,data FROM "+f.table+" ORDER BY id")) {
                result.next();check(result.getString(1)==null&&Arrays.equals(result.getBytes(2),new byte[]{4}),"constraint_exact_null_and_first_binary_row");
                result.next();check(result.getString(1).equals("second")&&Arrays.equals(result.getBytes(2),new byte[]{5}),"constraint_exact_second_binary_row");
            }
        }
    }
    private static void uncertainCommit()throws Exception {
        try(var f=fixture("uncertain")) {
            var real=f.connection;
            var wrapped=(Connection)Proxy.newProxyInstance(MysqlStorageRecoveryTest.class.getClassLoader(),new Class<?>[]{Connection.class},(owner,method,args)->{
                try {var result=method.invoke(real,args);if(method.getName().equals("commit"))throw new SQLException("Injected lost commit reply after actual MySQL COMMIT","08007");return result;}
                catch(InvocationTargetException failure){throw failure.getCause();}
            });
            var field=Database.class.getDeclaredField("connection");field.setAccessible(true);field.set(f.database,wrapped);
            f.database.queue.add(write(f,1,"actually committed",null));f.database.queue.execute();
            check(count(f)==1&&f.database.queue.pendingCount()==1,"uncertain_actual_commit_with_injected_missing_reply_keeps_original_pending");
            f.database.queue.execute();check(count(f)==1&&f.database.queue.pendingCount()==1,"uncertain_commit_never_blindly_retries");
            try{f.database.read("SELECT * FROM "+f.table,q->{throw new AssertionError("Uncertain read reached driver");});throw new AssertionError("Uncertain read allowed");}
            catch(SQLException expected){check(true,"uncertain_commit_read_requires_review");}
        }
    }
    private static void reads(boolean commit)throws Exception {
        String label=commit?"read_commit":"read_rollback";
        try(var f=fixture(label)) {
            if(!commit)sql(f.observer,"CREATE TABLE users(id INT PRIMARY KEY,name VARCHAR(16) NOT NULL,uuid VARCHAR(36) UNIQUE) ENGINE=InnoDB");
            var repository=new UserRepository(f.database);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var reading=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try {
                var row=f.database.prepareQueuedStatement("INSERT INTO users VALUES(1,'committed 汉',NULL)");
                QueuedWrite barrier=connection->{entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new SQLException("Barrier timeout");}catch(InterruptedException e){throw new SQLException(e);}if(!commit)throw new SQLException("Expected real MySQL rollback");};
                var writer=pool.submit(()->f.database.executeWrites(List.of(row.freeze(false),barrier)));
                check(entered.await(3,TimeUnit.SECONDS),label+"_actual_insert_remains_in_uncommitted_transaction");
                var read=pool.submit(()->{reading.countDown();return repository.getAllUsernames();});
                check(reading.await(3,TimeUnit.SECONDS),label+"_actual_public_repository_started");
                try{read.get(150,TimeUnit.MILLISECONDS);throw new AssertionError("Dirty MySQL read returned");}catch(TimeoutException expected){check(true,label+"_lookup_waits_for_transaction_outcome");}
                try(var query=f.observer.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM users")){check(rows.next()&&rows.getInt(1)==0,label+"_independent_mysql_connection_sees_no_dirty_user");}
                release.countDown();check(writer.get(3,TimeUnit.SECONDS)==commit,label+"_actual_transaction_reaches_expected_outcome");
                check(read.get(3,TimeUnit.SECONDS).equals(commit?Map.of(1,"committed 汉"):Map.of()),label+"_public_map_contains_only_exact_committed_users");
            }finally{release.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }
    public static void main(String[] args)throws Exception {
        settings=new Properties();try(var reader=Files.newBufferedReader(Path.of(args[0]))){settings.load(reader);}
        Class.forName("com.mysql.cj.jdbc.Driver");
        try(var db=DriverManager.getConnection(settings.getProperty("url"),settings.getProperty("user"),settings.getProperty("password"))){System.out.println("[MYSQLSTORAGE] SERVER "+db.getMetaData().getDatabaseProductVersion()+" DRIVER "+db.getMetaData().getDriverVersion());}
        lockRetry(false);lockRetry(true);batchConstraint();uncertainCommit();reads(false);reads(true);
        System.out.println("[MYSQLSTORAGE] COMPLETE "+checks+" checks");
    }
}
