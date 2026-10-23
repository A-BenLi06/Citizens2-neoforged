import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.daqem.grieflogger.database.Database;
import com.daqem.grieflogger.database.queue.QueuedStatement;
import com.daqem.grieflogger.database.queue.QueuedWrite;
import com.daqem.grieflogger.database.repository.UserRepository;

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
    private static void fullDatabase(boolean batch) throws Exception {
        String label = batch ? "full_batch" : "full_ordinary";
        try (var fixture = fixture(label)) {
            sql(fixture.connection, "PRAGMA max_page_count=2");
            var rollbacks = new AtomicInteger();
            ((org.sqlite.SQLiteConnection)fixture.connection).addCommitListener(new org.sqlite.SQLiteCommitListener() {
                public void onCommit() {}
                public void onRollback() { rollbacks.incrementAndGet(); }
            });
            var queue = batch ? fixture.database.batchQueue : fixture.database.queue;
            var first = write(fixture.database, 1, "original-prefix", new byte[]{3, 7});
            byte[] payload = new byte[32768];
            for (int i = 0; i < payload.length; i++) payload[i] = (byte)(i * 31);
            if (batch) {
                first.addBatch(); first.setInt(1, 2); first.setString(2, null);
                first.setBytes(3, payload); first.addBatch(); queue.add(first);
            } else {
                queue.add(first); queue.add(write(fixture.database, 2, null, payload));
            }
            int originalPending = batch ? 1 : 2;
            Arrays.fill(payload, (byte)0);
            for (int attempt = 1; attempt <= 2; attempt++) {
                com.daqem.grieflogger.GriefLogger.lastFailure = null;
                queue.execute();
                check(com.daqem.grieflogger.GriefLogger.lastFailure instanceof org.sqlite.SQLiteException error
                                && error.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_FULL,
                        label + "_actual_sqlite_full_attempt_" + attempt);
                check(rollbacks.get() == attempt && count(fixture) == 0 && queue.pendingCount() == originalPending,
                        label + "_native_auto_rollback_retains_entire_original_prefix_attempt_" + attempt);
                check(!fixture.connection.getAutoCommit(), label + "_driver_remains_manual_commit_attempt_" + attempt);
            }
            var later = write(fixture.database, 3, "later", new byte[]{9});
            if (batch) later.addBatch();
            queue.add(later);
            sql(fixture.connection, "PRAGMA max_page_count=100");
            queue.execute();
            check(queue.pendingCount() == 0 && count(fixture) == 3, label + "_capacity_restored_drains_original_and_later_once");
            try (var query = fixture.observer.createStatement(); var rows = query.executeQuery("SELECT id,value,data FROM observations ORDER BY id")) {
                rows.next(); check(rows.getInt(1) == 1 && rows.getString(2).equals("original-prefix")
                        && Arrays.equals(rows.getBytes(3), new byte[]{3,7}), label + "_exact_prefix_parameters");
                rows.next(); byte[] actual = rows.getBytes(3);
                boolean exact = actual.length == 32768;
                for (int i = 0; exact && i < actual.length; i++) exact = actual[i] == (byte)(i * 31);
                check(rows.getInt(1) == 2 && rows.getString(2) == null && exact, label + "_exact_owned_large_binary_and_null");
                rows.next(); check(rows.getInt(1) == 3 && rows.getString(2).equals("later")
                        && Arrays.equals(rows.getBytes(3), new byte[]{9}), label + "_exact_later_parameters");
            }
            queue.execute();
            check(count(fixture) == 3, label + "_committed_retry_never_duplicates");
            try (var query = fixture.observer.createStatement(); var rows = query.executeQuery("PRAGMA quick_check")) {
                check(rows.next() && rows.getString(1).equals("ok") && !rows.next(), label + "_sqlite_integrity_after_retry");
            }
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
    private static void fullModeFence(String current, String next) throws Exception {
        String label = "full_mode_" + current + "_" + next;
        try (var fixture = fixture(label, current)) {
            ((org.sqlite.SQLiteConnection)fixture.connection).getConnectionConfig()
                    .setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.valueOf(next));
            sql(fixture.connection, "PRAGMA max_page_count=2");
            fixture.database.queue.add(write(fixture.database, 1, "fenced", new byte[32768]));
            fixture.database.queue.execute();
            check(com.daqem.grieflogger.GriefLogger.lastFailure instanceof org.sqlite.SQLiteException error
                            && error.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_FULL,
                    label + "_actual_full_auto_abort");
            sql(fixture.connection, "PRAGMA max_page_count=100");
            fixture.database.queue.execute();
            check(count(fixture) == 0 && fixture.database.queue.pendingCount() == 1,
                    label + "_retains_conservative_non_deferred_fence");
            sql(fixture.connection, "BEGIN"); // Restore only test cleanup after the fenced abort.
        }
    }
    private static void fullAfterCommit() throws Exception {
        try (var fixture = fixture("fullaftercommit")) {
            var armed = new AtomicBoolean(true);
            CONNECTION.set(fixture.database, proxy(fixture.connection, (owner, method, arguments) -> {
                var result = invoke(fixture.connection, method, arguments);
                if (method.getName().equals("commit") && armed.compareAndSet(true, false))
                    throw new org.sqlite.SQLiteException("Injected FULL after successful real commit", org.sqlite.SQLiteErrorCode.SQLITE_FULL);
                return result;
            }));
            fixture.database.queue.add(write(fixture.database, 1, "saved", null));
            fixture.database.queue.execute();
            fixture.database.queue.add(write(fixture.database, 2, "later", null));
            fixture.database.queue.execute(); fixture.database.batchQueue.execute();
            check(count(fixture) == 1 && fixture.database.queue.pendingCount() == 2,
                    "typed_full_after_commit_cannot_replay_original_or_flush_later_writes");
        }
    }
    private static void legacyFull() throws Exception {
        try (var fixture = fixture("legacyfull")) {
            sql(fixture.connection, "PRAGMA max_page_count=2");
            var statement = fixture.database.prepareStatement(INSERT);
            statement.setInt(1, 1); statement.setString(2, "legacy"); statement.setBytes(3, new byte[32768]); statement.addBatch();
            fixture.database.batchQueue.add(statement); fixture.database.batchQueue.execute();
            check(com.daqem.grieflogger.GriefLogger.lastFailure instanceof org.sqlite.SQLiteException error
                            && error.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_FULL,
                    "legacy_batch_actual_full_auto_abort");
            sql(fixture.connection, "PRAGMA max_page_count=100");
            fixture.database.batchQueue.execute();
            check(count(fixture) == 0 && fixture.database.batchQueue.pendingCount() == 1,
                    "legacy_full_abort_cannot_retry_driver_discarded_parameters");
            statement.close();
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
            check(!fixture.database.flushPendingWrites() && count(fixture) == 1
                    && fixture.database.queue.pendingCount() == 2, "shutdown_drain_preserves_uncertain_commit_fence");
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
    private static void shutdownWaitsForWorker() throws Exception {
        try (var fixture = fixture("shutdown_race")) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var waiterStarted = new CountDownLatch(1);
            var real = fixture.database.prepareStatement("INSERT INTO observations VALUES(1,'active-worker',NULL)");
            var first = (PreparedStatement)Proxy.newProxyInstance(StorageRecoveryTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (owner, method, arguments) -> {
                if (method.getName().equals("executeUpdate")) {
                    entered.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new SQLException("Timed out waiting for test release");
                }
                return invoke(real, method, arguments);
            });
            fixture.database.queue.add(first);
            var pool = Executors.newFixedThreadPool(3);
            try {
                var worker = pool.submit(() -> fixture.database.queue.execute());
                check(entered.await(5,TimeUnit.SECONDS), "shutdown_worker_is_executing_actual_legacy_statement");
                fixture.database.queue.add(write(fixture.database,2,"later-ordinary",new byte[]{5}));
                var batch=write(fixture.database,3,null,new byte[]{6}); batch.addBatch();
                batch.setInt(1,4); batch.setString(2,"later-batch"); batch.setBytes(3,new byte[]{7}); batch.addBatch();
                fixture.database.batchQueue.add(batch);
                var waiter=pool.submit(() -> {waiterStarted.countDown();return fixture.database.flushPendingWrites();});
                check(waiterStarted.await(5,TimeUnit.SECONDS),"shutdown_drain_started_while_periodic_worker_is_active");
                try {waiter.get(100,TimeUnit.MILLISECONDS);throw new AssertionError("Shutdown drain returned before active worker");}
                catch(TimeoutException expected) {check(true,"shutdown_drain_waits_instead_of_skipping_busy_queue");}
                pool.submit(() -> fixture.database.queue.execute()).get(2,TimeUnit.SECONDS);
                check(true,"periodic_flush_remains_nonblocking_while_queue_is_active");
                release.countDown(); worker.get(5,TimeUnit.SECONDS);
                check(waiter.get(5,TimeUnit.SECONDS)&&count(fixture)==4&&fixture.database.queue.pendingCount()==0
                        &&fixture.database.batchQueue.pendingCount()==0,"shutdown_drain_waits_then_commits_later_ordinary_and_batch_prefixes_once");
                try(var query=fixture.observer.createStatement();var rows=query.executeQuery("SELECT id,value,hex(data) FROM observations ORDER BY id")) {
                    var actual=new ArrayList<String>();while(rows.next())actual.add(rows.getInt(1)+":"+rows.getString(2)+":"+rows.getString(3));
                    check(actual.equals(List.of("1:active-worker:","2:later-ordinary:05","3:null:06","4:later-batch:07")),"shutdown_drain_preserves_exact_worker_and_later_parameters");
                }
                check(fixture.database.flushPendingWrites()&&count(fixture)==4,"repeated_shutdown_drain_is_idempotent");
            } finally {release.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }
    private static void shutdownLocked() throws Exception {
        try(var fixture=fixture("shutdown_locked")) {
            fixture.database.queue.add(write(fixture.database,1,"ordinary",null));
            var batch=write(fixture.database,2,"batch",null);batch.addBatch();fixture.database.batchQueue.add(batch);
            sql(fixture.observer,"BEGIN IMMEDIATE");
            check(!fixture.database.flushPendingWrites()&&count(fixture)==0&&fixture.database.queue.pendingCount()==1
                    &&fixture.database.batchQueue.pendingCount()==1,"failed_shutdown_drain_reports_original_uncommitted_work");
            sql(fixture.observer,"ROLLBACK");
            check(fixture.database.flushPendingWrites()&&count(fixture)==2,"shutdown_drain_can_retry_after_known_rollback_and_unlock");
            fixture.database.queue.add(write(fixture.database,3,"next-server-session",null));
            check(fixture.database.flushPendingWrites()&&count(fixture)==3,
                    "same_process_database_and_queues_accept_next_server_session_after_clean_drain");
        }
    }
    private static void shutdownReentry() throws Exception {
        try(var fixture=fixture("shutdown_reentry")) {
            var rejected=new AtomicBoolean();
            var real=fixture.database.prepareStatement("INSERT INTO observations VALUES(1,'recursive',NULL)");
            var statement=(PreparedStatement)Proxy.newProxyInstance(StorageRecoveryTest.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(owner,method,arguments)-> {
                if(method.getName().equals("executeUpdate")) {
                    fixture.database.queue.execute();
                    try {fixture.database.flushPendingWrites();throw new AssertionError("Recursive blocking drain succeeded");}
                    catch(IllegalStateException expected) {rejected.set(true);}
                }
                return invoke(real,method,arguments);
            });
            fixture.database.queue.add(statement);
            check(fixture.database.flushPendingWrites()&&rejected.get(),"blocking_drain_rejects_own_callback_reentry_without_deadlock");
            check(count(fixture)==1&&fixture.database.queue.pendingCount()==0&&real.isClosed(),"periodic_callback_reentry_cannot_repeat_or_drop_original_write");
        }
    }
    private static void await(CountDownLatch latch) throws SQLException {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new SQLException("Test barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException(failure); }
    }
    private static void repositoryReadIsolation(boolean commit) throws Exception {
        String label = commit ? "lookup_commit" : "lookup_rollback";
        try (var fixture = fixture(label)) {
            sql(fixture.connection,"CREATE TABLE users(id INTEGER PRIMARY KEY,name TEXT NOT NULL,uuid TEXT UNIQUE)");
            fixture.connection.commit();
            var repository = new UserRepository(fixture.database);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var reading = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var insert = fixture.database.prepareQueuedStatement("INSERT INTO users VALUES(1,'original',NULL)");
                QueuedWrite barrier = connection -> { entered.countDown(); await(release); if (!commit) throw new SQLException("Expected lookup rollback"); };
                var writer = pool.submit(() -> fixture.database.executeWrites(List.of(insert.freeze(false),barrier)));
                check(entered.await(3,TimeUnit.SECONDS),label+"_real_insert_reaches_uncommitted_barrier");
                var read = pool.submit(() -> {reading.countDown(); return repository.getAllUsernames();});
                check(reading.await(3,TimeUnit.SECONDS),label+"_public_lookup_started");
                try {read.get(150,TimeUnit.MILLISECONDS); throw new AssertionError("Public lookup exposed an active write");}
                catch (TimeoutException expected) {check(true,label+"_public_lookup_waits_for_transaction_outcome");}
                try(var query=fixture.observer.createStatement();var rows=query.executeQuery("SELECT COUNT(*) FROM users")) {
                    check(rows.next()&&rows.getInt(1)==0,label+"_independent_reader_sees_no_uncommitted_row");
                }
                release.countDown();
                check(writer.get(3,TimeUnit.SECONDS)==commit,label+"_expected_native_transaction_outcome");
                check(read.get(3,TimeUnit.SECONDS).equals(commit?Map.of(1,"original"):Map.of()),label+"_lookup_returns_only_exact_committed_state");
                check(repository.getAllUsernames().equals(commit?Map.of(1,"original"):Map.of()),label+"_subsequent_lookup_remains_exact");
            } finally {release.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }
    private static void cursorOwnsMonitor() throws Exception {
        try(var fixture=fixture("lookup_cursor")) {
            sql(fixture.observer,"INSERT INTO observations VALUES(1,'original',NULL),(2,'second',NULL)");
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var writing=new CountDownLatch(1);
            var statement=new AtomicReference<PreparedStatement>();var cursor=new AtomicReference<ResultSet>();
            var pool=Executors.newFixedThreadPool(2);
            try {
                var read=pool.submit(()->fixture.database.read("SELECT value FROM observations ORDER BY id",prepared->{
                    statement.set(prepared);
                    try(var rows=prepared.executeQuery()) {
                        cursor.set(rows);if(!rows.next())throw new AssertionError("Missing first row");
                        var first=rows.getString(1);entered.countDown();await(release);
                        if(!rows.next())throw new AssertionError("Missing second row");
                        var result=List.of(first,rows.getString(1));if(rows.next())throw new AssertionError("Dirty extra row");return result;
                    }
                }));
                check(entered.await(3,TimeUnit.SECONDS),"lookup_cursor_stays_open_between_rows");
                var insert=write(fixture.database,3,"later",null).freeze(false);
                var writer=pool.submit(()->{writing.countDown();return fixture.database.executeWrites(List.of(insert));});
                check(writing.await(3,TimeUnit.SECONDS),"lookup_cursor_concurrent_writer_started");
                try {writer.get(150,TimeUnit.MILLISECONDS);throw new AssertionError("Writer ran during result consumption");}
                catch(TimeoutException expected){check(count(fixture)==2,"lookup_cursor_blocks_writer_through_complete_result_consumption");}
                release.countDown();
                check(read.get(3,TimeUnit.SECONDS).equals(List.of("original","second")),"lookup_cursor_preserves_exact_complete_snapshot");
                check(writer.get(3,TimeUnit.SECONDS)&&count(fixture)==3,"lookup_cursor_release_allows_next_write_commit");
                check(statement.get().isClosed()&&cursor.get().isClosed(),"lookup_cursor_and_statement_close_before_monitor_release");
            } finally {release.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        }
    }
    private static void readFailures() throws Exception {
        try(var fixture=fixture("lookup_failures")) {
            for(boolean runtime:new boolean[]{false,true}) {
                var statement=new AtomicReference<PreparedStatement>();var cursor=new AtomicReference<ResultSet>();
                try {
                    fixture.database.read("SELECT 1",prepared->{statement.set(prepared);cursor.set(prepared.executeQuery());
                        if(runtime)throw new IllegalArgumentException("Expected decoder failure");throw new SQLException("Expected query failure");});
                    throw new AssertionError("Read failure swallowed");
                } catch(SQLException|IllegalArgumentException expected) {
                    check(runtime?expected instanceof IllegalArgumentException:expected instanceof SQLException,"lookup_propagates_original_failure_"+runtime);
                }
                check(statement.get().isClosed()&&cursor.get().isClosed(),"lookup_failure_closes_statement_and_its_cursor_"+runtime);
                var pool=Executors.newSingleThreadExecutor();
                try {check(pool.submit(()->fixture.database.executeWrites(List.of(write(fixture.database,runtime?2:1,"after-read-failure",null).freeze(false)))).get(3,TimeUnit.SECONDS),"lookup_failure_releases_monitor_for_other_writer_"+runtime);}
                finally {pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
            }
            var rejected=new AtomicBoolean();
            QueuedWrite reentrant=connection->{
                try {fixture.database.read("SELECT * FROM observations",prepared->{throw new AssertionError("Read inside own write reached JDBC");});throw new AssertionError("Reentrant read accepted");}
                catch(SQLException expected){rejected.set(true);}
            };
            check(fixture.database.executeWrites(List.of(reentrant))&&rejected.get(),"lookup_rejects_same_thread_write_callback_reentry_without_deadlock");
            var real=fixture.connection;
            CONNECTION.set(fixture.database,proxy(real,(owner,method,args)->{
                if(method.getName().equals("rollback"))throw new SQLException("Expected rollback failure");return invoke(real,method,args);
            }));
            QueuedWrite failed=connection->{throw new SQLException("Expected write failure");};
            check(!fixture.database.executeWrites(List.of(write(fixture.database,3,"dirty",null).freeze(false),failed)),"lookup_fence_retains_failed_rollback_outcome");
            try {fixture.database.read("SELECT * FROM observations",prepared->{throw new AssertionError("Uncertain read reached JDBC");});throw new AssertionError("Uncertain read accepted");}
            catch(SQLException expected){check(true,"lookup_rejects_uncertain_transaction_instead_of_exposing_dirty_state");}
            check(count(fixture)==2,"lookup_fence_keeps_uncommitted_prefix_invisible_to_independent_reader");
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
        fullDatabase(false); fullDatabase(true);
        fullModeFence("IMMEDIATE", "IMMEDIATE"); fullModeFence("DEFERRED", "IMMEDIATE");
        fullModeFence("IMMEDIATE", "DEFERRED"); fullAfterCommit(); legacyFull();
        shutdownWaitsForWorker(); shutdownLocked(); shutdownReentry();
        repositoryReadIsolation(false); repositoryReadIsolation(true); cursorOwnsMonitor(); readFailures();
        System.out.println("[GRIEFSTORAGE] COMPLETE " + passed + " checks");
    }
}
