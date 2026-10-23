import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.daqem.grieflogger.database.Database;
import com.daqem.grieflogger.database.queue.QueuedStatement;

/** Actual provider bytecode and SQLite, with independently committed/read database state. */
public final class StorageRecoveryTest {
    private static Path root;
    private static int passed;
    private static final String INSERT = "INSERT INTO observations(id, value, data) VALUES (?, ?, ?)";
    private static final Field CONNECTION;
    static {
        try { CONNECTION = Database.class.getDeclaredField("connection"); CONNECTION.setAccessible(true); }
        catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private record Fixture(Connection connection, Connection observer, Database database) implements AutoCloseable {
        public void close() throws SQLException { try { connection.rollback(); } finally { connection.close(); observer.close(); } }
    }
    private static Fixture fixture(String name) throws Exception {
        return fixture(name, "DEFERRED");
    }
    private static Fixture fixture(String name, String mode) throws Exception {
        var path = root.resolve(name + ".db");
        if (Files.exists(path)) throw new IllegalStateException("Refusing existing test database");
        var properties = new Properties(); properties.setProperty("transaction_mode", mode);
        var connection = DriverManager.getConnection("jdbc:sqlite:" + path, properties);
        var observer = DriverManager.getConnection("jdbc:sqlite:" + path);
        connection.createStatement().execute("PRAGMA busy_timeout=20");
        connection.setAutoCommit(false);
        try (var schema = connection.createStatement()) { schema.execute("CREATE TABLE observations(id INTEGER PRIMARY KEY, value TEXT, data BLOB)"); }
        var database = new Database(); CONNECTION.set(database, connection);
        check(database.completeInitialization(), name + "_schema_commits_before_event_registration");
        return new Fixture(connection, observer, database);
    }
    private static QueuedStatement write(Database database, int id, String value, byte[] bytes) throws SQLException {
        var statement = database.prepareQueuedStatement(INSERT);
        statement.setInt(1, id); statement.setString(2, value); statement.setBytes(3, bytes);
        return statement;
    }
    private static int count(Fixture fixture) throws SQLException {
        try (var query = fixture.observer.createStatement(); var rows = query.executeQuery("SELECT COUNT(*) FROM observations")) { rows.next(); return rows.getInt(1); }
    }
    private static void sql(Connection connection, String query) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(query); }
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; System.out.println("[GRIEFSTORAGE] PASS " + name);
    }
    private static Connection proxy(Connection real, InvocationHandler handler) {
        return (Connection) Proxy.newProxyInstance(StorageRecoveryTest.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    }
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void transientLock() throws Exception {
        try (var fixture = fixture("locked")) {
            var database = fixture.database;
            var bytes = new byte[]{1, 2, 3};
            var statement = write(database, 1, "retained", bytes);
            database.queue.add(statement); bytes[0] = 9;
            try { statement.setInt(1, 99); throw new AssertionError("Queued builder remained mutable"); }
            catch (SQLException expected) { check(true, "queued_parameters_freeze_and_own_binary_input"); }
            sql(fixture.observer, "BEGIN IMMEDIATE");
            database.queue.execute();
            check(database.queue.pendingCount() == 1 && count(fixture) == 0, "locked_write_remains_pending_without_false_commit");
            sql(fixture.observer, "ROLLBACK");
            database.queue.execute();
            check(database.queue.pendingCount() == 0 && count(fixture) == 1, "unlocked_retry_commits_original_write");
            try (var query = fixture.observer.createStatement(); var rows = query.executeQuery("SELECT id,value,data FROM observations")) {
                rows.next(); check(rows.getInt(1) == 1 && rows.getString(2).equals("retained") && Arrays.equals(rows.getBytes(3), new byte[]{1,2,3}), "retry_preserves_exact_original_parameters");
            }
            database.queue.execute(); database.queue.hello();
            check(count(fixture) == 1, "committed_retry_and_ping_do_not_duplicate_write");
        }
    }
    private static void batchRollback() throws Exception {
        try (var fixture = fixture("batch")) {
            sql(fixture.observer, "INSERT INTO observations VALUES (2,'existing',NULL)");
            var statement = write(fixture.database, 1, null, new byte[]{4}); statement.addBatch();
            statement.setInt(1, 2); statement.setString(2, "second"); statement.setBytes(3, new byte[]{5}); statement.addBatch();
            fixture.database.batchQueue.add(statement);
            fixture.database.batchQueue.execute();
            check(fixture.database.batchQueue.pendingCount() == 1 && count(fixture) == 1, "failed_batch_rolls_back_successful_prefix_and_retains_all_rows");
            sql(fixture.observer, "DELETE FROM observations WHERE id=2");
            fixture.database.batchQueue.execute();
            check(fixture.database.batchQueue.pendingCount() == 0 && count(fixture) == 2, "retry_recreates_driver_discarded_batch_parameters");
            try (var query = fixture.observer.createStatement(); var rows = query.executeQuery("SELECT id,value,data FROM observations ORDER BY id")) {
                rows.next(); check(rows.getInt(1) == 1 && rows.getString(2) == null && Arrays.equals(rows.getBytes(3), new byte[]{4}), "batch_retry_preserves_null_and_first_binary_row");
                rows.next(); check(rows.getInt(1) == 2 && rows.getString(2).equals("second") && Arrays.equals(rows.getBytes(3), new byte[]{5}), "batch_retry_preserves_distinct_second_row");
            }
            fixture.database.batchQueue.execute(); check(count(fixture) == 2, "batch_retry_after_commit_is_noop");
        }
    }
    private static void readerBlockedCommit(boolean batch) throws Exception {
        String label = batch ? "reader_batch" : "reader_ordinary";
        try (var fixture = fixture(label)) {
            var queue = batch ? fixture.database.batchQueue : fixture.database.queue;
            var original = write(fixture.database, 1, "reader-retained", new byte[]{6, 7});
            if (batch) {
                original.addBatch(); original.setInt(1, 2); original.setString(2, null);
                original.setBytes(3, new byte[]{8, 9}); original.addBatch();
            }
            queue.add(original);
            sql(fixture.observer, "BEGIN");
            check(count(fixture) == 0, label + "_reader_holds_original_snapshot");
            com.daqem.grieflogger.GriefLogger.lastFailure = null;
            queue.execute();
            var failure = com.daqem.grieflogger.GriefLogger.lastFailure;
            check(failure instanceof org.sqlite.SQLiteException sqlite
                    && sqlite.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_BUSY,
                    label + "_actual_sqlite_commit_busy");
            check(queue.pendingCount() == 1 && count(fixture) == 0,
                    label + "_failed_commit_retains_original_without_visible_rows");
            sql(fixture.observer, "ROLLBACK");
            queue.execute();
            check(queue.pendingCount() == 0 && count(fixture) == (batch ? 2 : 1),
                    label + "_unlocked_retry_drains_original_once");
            try (var query = fixture.observer.createStatement(); var rows = query.executeQuery("SELECT id,value,data FROM observations ORDER BY id")) {
                rows.next(); check(rows.getInt(1) == 1 && rows.getString(2).equals("reader-retained")
                        && Arrays.equals(rows.getBytes(3), new byte[]{6,7}), label + "_exact_original_parameters");
                if (batch) {
                    rows.next(); check(rows.getInt(1) == 2 && rows.getString(2) == null
                            && Arrays.equals(rows.getBytes(3), new byte[]{8,9}), label + "_exact_second_row");
                }
            }
            queue.execute(); check(count(fixture) == (batch ? 2 : 1), label + "_repeat_is_noop");
        }
    }
    private static void ordinaryRollback() throws Exception {
        try (var fixture = fixture("prefix")) {
            sql(fixture.observer, "INSERT INTO observations VALUES (2,'existing',NULL)");
            fixture.database.queue.add(write(fixture.database, 1, "first", null));
            fixture.database.queue.add(write(fixture.database, 2, "second", null));
            fixture.database.queue.execute();
            check(fixture.database.queue.pendingCount() == 2 && count(fixture) == 1, "ordinary_failed_prefix_neither_commits_nor_drops_pending_writes");
            sql(fixture.observer, "DELETE FROM observations WHERE id=2");
            fixture.database.queue.execute();
            check(fixture.database.queue.pendingCount() == 0 && count(fixture) == 2, "ordinary_retry_replays_whole_rolled_back_prefix_once");
        }
    }
    private static void conservativeCommitModes(String current, String next) throws Exception {
        String label = "mode_" + current + "_" + next;
        try (var fixture = fixture(label, current)) {
            ((org.sqlite.SQLiteConnection)fixture.connection).getConnectionConfig()
                    .setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.valueOf(next));
            fixture.database.queue.add(write(fixture.database, 1, "fenced", null));
            sql(fixture.observer, "BEGIN"); count(fixture);
            fixture.database.queue.execute();
            check(com.daqem.grieflogger.GriefLogger.lastFailure instanceof org.sqlite.SQLiteException error
                    && error.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_BUSY,
                    label + "_real_commit_busy");
            sql(fixture.observer, "ROLLBACK");
            fixture.database.queue.execute();
            check(count(fixture) == 0 && fixture.database.queue.pendingCount() == 1,
                    label + "_remains_fenced_without_known_deferred_path");
        }
    }
    private static void wrappedBusyAfterCommit() throws Exception {
        try (var fixture = fixture("wrappedbusy")) {
            var armed = new AtomicBoolean(true);
            CONNECTION.set(fixture.database, proxy(fixture.connection, (owner, method, arguments) -> {
                var result = invoke(fixture.connection, method, arguments);
                if (method.getName().equals("commit") && armed.compareAndSet(true, false))
                    throw new org.sqlite.SQLiteException("Injected BUSY after successful real commit", org.sqlite.SQLiteErrorCode.SQLITE_BUSY);
                return result;
            }));
            fixture.database.queue.add(write(fixture.database, 1, "saved", null));
            fixture.database.queue.execute();
            fixture.database.queue.add(write(fixture.database, 2, "later", null));
            fixture.database.queue.execute();
            check(count(fixture) == 1 && fixture.database.queue.pendingCount() == 2,
                    "wrapped_busy_after_successful_commit_cannot_replay_or_flush_later_writes");
        }
    }
    private static void ambiguousCommit() throws Exception {
        try (var fixture = fixture("commit")) {
            var armed = new AtomicBoolean(true);
            CONNECTION.set(fixture.database, proxy(fixture.connection, (owner, method, arguments) -> {
                var result = invoke(fixture.connection, method, arguments);
                if (method.getName().equals("commit") && armed.compareAndSet(true, false)) throw new SQLException("Injected lost acknowledgment after real commit");
                return result;
            }));
            fixture.database.queue.add(write(fixture.database, 1, "committed", null));
            fixture.database.queue.execute();
            check(count(fixture) == 1 && fixture.database.queue.pendingCount() == 1, "lost_commit_ack_retains_pending_original_despite_committed_row");
            fixture.database.queue.add(write(fixture.database, 2, "later", null));
            fixture.database.queue.execute(); fixture.database.batchQueue.execute();
            check(count(fixture) == 1 && fixture.database.queue.pendingCount() == 2, "ambiguous_commit_stops_replay_and_later_writes");
        }
    }
    private static void failedRollback() throws Exception {
        try (var fixture = fixture("rollback")) {
            sql(fixture.observer, "INSERT INTO observations VALUES (2,'existing',NULL)");
            CONNECTION.set(fixture.database, proxy(fixture.connection, (owner, method, arguments) -> {
                if (method.getName().equals("rollback")) throw new SQLException("Injected rollback failure");
                return invoke(fixture.connection, method, arguments);
            }));
            fixture.database.queue.add(write(fixture.database, 1, "uncertain", null));
            fixture.database.queue.add(write(fixture.database, 2, "conflict", null));
            fixture.database.queue.execute(); fixture.database.queue.execute();
            check(count(fixture) == 1 && fixture.database.queue.pendingCount() == 2, "failed_rollback_retains_uncommitted_prefix_without_retry_or_commit");
        }
    }
    private static void legacyContracts() throws Exception {
        try (var fixture = fixture("legacy")) {
            var original = fixture.database.prepareStatement("INSERT INTO observations VALUES(1,'legacy',NULL)");
            var statement = (PreparedStatement) Proxy.newProxyInstance(StorageRecoveryTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (owner, method, arguments) -> {
                var result = invoke(original, method, arguments);
                if (method.getName().equals("close")) throw new SQLException("Injected close error after actual close");
                return result;
            });
            fixture.database.queue.add(statement); fixture.database.queue.execute(); fixture.database.queue.execute();
            check(count(fixture) == 1 && fixture.database.queue.pendingCount() == 0 && original.isClosed(), "post_commit_close_failure_cannot_replay_legacy_write");
        }
        try (var fixture = fixture("legacybatch"); var statement = fixture.database.prepareStatement("INSERT INTO observations(id) VALUES(?)")) {
            sql(fixture.observer, "INSERT INTO observations VALUES(2,'existing',NULL)");
            statement.setInt(1, 1); statement.addBatch(); statement.setInt(1, 2); statement.addBatch();
            fixture.database.batchQueue.add(statement); fixture.database.batchQueue.execute();
            sql(fixture.observer, "DELETE FROM observations WHERE id=2");
            fixture.database.batchQueue.execute();
            check(count(fixture) == 0 && fixture.database.batchQueue.pendingCount() == 1, "failed_legacy_driver_batch_requires_review_instead_of_empty_retry");
        }
    }
    private static void concurrentProducers() throws Exception {
        try (var fixture = fixture("concurrent")) {
            var pool = Executors.newFixedThreadPool(6); var done = new AtomicBoolean();
            try {
                var flushers = new ArrayList<Future<?>>();
                for (int flusher=0; flusher<2; flusher++) flushers.add(pool.submit(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
                    while (!done.get() || fixture.database.queue.pendingCount() + fixture.database.batchQueue.pendingCount() != 0) {
                        if (System.nanoTime() > deadline) throw new AssertionError("Concurrent flush did not drain");
                        fixture.database.queue.execute(); fixture.database.batchQueue.execute(); Thread.yield();
                    }
                }));
                var writers = new ArrayList<Future<?>>();
                for (int producer=0; producer<4; producer++) {
                    final int base=producer*150;
                    writers.add(pool.submit(() -> {
                        for (int row=0; row<50; row++) {
                            try {
                                int id=base+row*3+1;
                                fixture.database.queue.add(write(fixture.database,id,"ordinary",null));
                                var batch=write(fixture.database,id+1,"batch",null); batch.addBatch(); batch.setInt(1,id+2); batch.addBatch(); fixture.database.batchQueue.add(batch);
                            } catch (SQLException failure) { throw new RuntimeException(failure); }
                        }
                    }));
                }
                for (var writer : writers) writer.get(35,TimeUnit.SECONDS);
                done.set(true);
                for (var flusher : flushers) flusher.get(35,TimeUnit.SECONDS);
                check(count(fixture)==600 && fixture.database.queue.pendingCount()==0 && fixture.database.batchQueue.pendingCount()==0, "concurrent_producers_and_both_flushers_retain_all_600_distinct_rows");
                try (var query=fixture.observer.createStatement(); var rows=query.executeQuery("SELECT MIN(id),MAX(id),SUM(id) FROM observations")) {
                    rows.next(); check(rows.getInt(1)==1 && rows.getInt(2)==600 && rows.getLong(3)==180300L, "concurrent_flush_preserves_exact_admitted_row_identities");
                }
            } finally { done.set(true); pool.shutdownNow(); pool.awaitTermination(5,TimeUnit.SECONDS); }
        }
    }
    public static void main(String[] arguments) throws Exception {
        root=Path.of(arguments[0]); Class.forName("org.sqlite.JDBC");
        System.out.println("PROVIDER " + Database.class.getProtectionDomain().getCodeSource().getLocation());
        transientLock(); batchRollback(); ordinaryRollback(); ambiguousCommit(); failedRollback(); legacyContracts(); concurrentProducers();
        readerBlockedCommit(false); readerBlockedCommit(true);
        conservativeCommitModes("IMMEDIATE", "IMMEDIATE");
        conservativeCommitModes("DEFERRED", "IMMEDIATE");
        conservativeCommitModes("IMMEDIATE", "DEFERRED"); wrappedBusyAfterCommit();
        System.out.println("[GRIEFSTORAGE] COMPLETE " + passed + " checks");
    }
}
