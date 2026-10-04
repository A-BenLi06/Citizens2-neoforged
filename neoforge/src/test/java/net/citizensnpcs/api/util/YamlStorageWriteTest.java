package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

public class YamlStorageWriteTest {
    @Test void serializationFailurePreservesTheExistingFileAndAllowsRetry(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("state.yml");
        byte[] previous = "# previous document\nvalue: retained\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, previous);
        YamlStorage storage = new YamlStorage(file.toFile());
        assertTrue(storage.load());
        storage.getKey("").setRaw("broken", new BrokenBean());
        assertThrows(RuntimeException.class, storage::save);
        assertArrayEquals(previous, Files.readAllBytes(file));
        try (var children = Files.list(directory)) { assertEquals(List.of(file), children.toList(), "temporary files must be cleaned up"); }
        storage.getKey("").removeKey("broken");
        storage.getKey("").setString("value", "retry"); storage.save();
        assertTrue(storage.load());
        assertEquals("retry", storage.getKey("").getString("value"));
    }

    @Test void immediateSaveWaitsForAnOlderAsyncWrite(@TempDir Path directory) throws Exception {
        verifyWriteOrder(directory, false, false);
    }

    @Test void aFailedAsyncWriteDoesNotPreventTheLaterImmediateSave(@TempDir Path directory) throws Exception {
        verifyWriteOrder(directory, true, false);
    }

    @Test void separateStorageInstancesShareTheWriteOrder(@TempDir Path directory) throws Exception {
        verifyWriteOrder(directory, false, true);
    }

    private void verifyWriteOrder(Path directory, boolean failOlder, boolean separateInstance) throws Exception {
        Path file = directory.resolve("ordered.yml"); Files.writeString(file, "value: initial\n");
        YamlStorage storage = new YamlStorage(file.toFile());
        BlockingBean blocker = new BlockingBean(failOlder);
        storage.getKey("").setString("value", "older"); storage.getKey("").setRaw("blocked", blocker);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            storage.saveAsync();
            assertTrue(blocker.entered.await(5, TimeUnit.SECONDS), "older serialization must have started");
            CountDownLatch started = new CountDownLatch(1);
            Future<?> newer = executor.submit(() -> {
                YamlStorage destination = separateInstance ? new YamlStorage(file.toFile()) : storage;
                destination.getKey("").removeKey("blocked"); destination.getKey("").setString("value", "latest");
                started.countDown(); destination.save();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            boolean completedEarly;
            try { newer.get(500, TimeUnit.MILLISECONDS); completedEarly = true; }
            catch (TimeoutException expectedWhileOlderIsBlocked) { completedEarly = false; }
            finally { blocker.release.countDown(); }
            newer.get(5, TimeUnit.SECONDS);
            assertTrue(ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS));
            assertFalse(completedEarly, "an immediate save must wait for previously queued writes");
            assertTrue(storage.load());
            assertEquals("latest", storage.getKey("").getString("value"));
        } finally {
            blocker.release.countDown();
            assertTrue(ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS));
        }
    }

    @Test void asyncSnapshotDetachesMutableBinaryAndSequenceValues(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("snapshot.yml"); Files.writeString(file, "value: initial\n");
        YamlStorage storage = new YamlStorage(file.toFile(), null, false);
        BlockingBean blocker = new BlockingBean(false);
        byte[] binary = { 1, 2 }; List<String> sequence = new ArrayList<>(List.of("captured"));
        storage.getKey("").setRaw("blocked", blocker);
        storage.getKey("").setRaw("binary", binary); storage.getKey("").setRaw("sequence", sequence);
        try {
            storage.saveAsync(); assertTrue(blocker.entered.await(5, TimeUnit.SECONDS));
            binary[0] = 9; sequence.add("later");
        } finally { blocker.release.countDown(); }
        assertTrue(ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS));
        // Compose nodes instead of constructing the synthetic blocking bean from its YAML tag.
        org.yaml.snakeyaml.LoaderOptions options = new org.yaml.snakeyaml.LoaderOptions();
        options.setTagInspector(tag -> tag.getClassName().equals(BlockingBean.class.getName()));
        MappingNode document = (MappingNode) new Yaml(options).compose(new StringReader(Files.readString(file)));
        ScalarNode storedBinary = (ScalarNode) field(document, "binary");
        assertArrayEquals(new byte[] { 1, 2 }, Base64.getMimeDecoder().decode(storedBinary.getValue()));
        SequenceNode storedSequence = (SequenceNode) field(document, "sequence");
        assertEquals(List.of("captured"), storedSequence.getValue().stream().map(node -> ((ScalarNode) node).getValue()).toList());
    }

    @Test void reloadWaitsForPreviouslyQueuedWrites(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("reload.yml"); Files.writeString(file, "value: initial\n");
        YamlStorage storage = new YamlStorage(file.toFile());
        BlockingBean blocker = new BlockingBean(false);
        storage.getKey("").setRaw("blocked", blocker);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            storage.saveAsync(); assertTrue(blocker.entered.await(5, TimeUnit.SECONDS));
            storage.getKey("").removeKey("blocked"); storage.getKey("").setString("value", "latest"); storage.saveAsync();
            CountDownLatch started = new CountDownLatch(1);
            Future<Boolean> reloaded = executor.submit(() -> { started.countDown(); return storage.load(); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            boolean completedEarly;
            try { reloaded.get(500, TimeUnit.MILLISECONDS); completedEarly = true; }
            catch (TimeoutException expectedWhileOlderIsBlocked) { completedEarly = false; }
            finally { blocker.release.countDown(); }
            assertTrue(reloaded.get(5, TimeUnit.SECONDS));
            assertTrue(ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS));
            assertFalse(completedEarly, "reload must observe the completed queued snapshot");
            assertEquals("latest", storage.getKey("").getString("value"));
        } finally {
            blocker.release.countDown();
            assertTrue(ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS));
        }
    }

    private static Node field(MappingNode root, String name) {
        return root.getValue().stream().filter(entry -> ((ScalarNode) entry.getKeyNode()).getValue().equals(name)).findFirst().orElseThrow().getValueNode();
    }

    @Test void existingFilePermissionsAndHiddenStateSurviveReplacement(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("attributes.yml"); Files.writeString(file, "value: initial\n");
        var acl = Files.getFileAttributeView(file, java.nio.file.attribute.AclFileAttributeView.class);
        var beforeAcl = acl == null ? null : acl.getAcl();
        var dos = Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView.class);
        YamlStorage storage = new YamlStorage(file.toFile()); storage.getKey("").setString("value", "updated");
        try {
            if (dos != null) {
                dos.setHidden(true); dos.setReadOnly(true);
                byte[] before = Files.readAllBytes(file);
                assertThrows(java.io.UncheckedIOException.class, storage::save);
                assertArrayEquals(before, Files.readAllBytes(file));
                dos.setReadOnly(false);
            }
            storage.save();
            if (acl != null) assertEquals(beforeAcl, acl.getAcl());
            if (dos != null) assertTrue(dos.readAttributes().isHidden());
            assertTrue(storage.load()); assertEquals("updated", storage.getKey("").getString("value"));
        } finally {
            if (dos != null) { dos.setReadOnly(false); dos.setHidden(false); }
        }
    }

    @Test void recursiveAsyncSerializationIsRejectedWithoutQueuingAnotherWrite(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("recursive.yml"); Files.writeString(file, "value: initial\n");
        YamlStorage storage = new YamlStorage(file.toFile());
        RecursiveBean bean = new RecursiveBean(storage); storage.getKey("").setRaw("recursive", bean);
        assertDoesNotThrow(storage::save);
        assertTrue(ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS));
        assertEquals(1, bean.calls);
    }

    @Test void aCyclicArrayIsRejectedBeforeChangingTheFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("cycle.yml"); Files.writeString(file, "value: initial\n");
        byte[] before = Files.readAllBytes(file);
        YamlStorage storage = new YamlStorage(file.toFile());
        Object[] cycle = new Object[1]; cycle[0] = cycle; storage.getKey("").setRaw("cycle", cycle);
        assertThrows(IllegalArgumentException.class, storage::save);
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void synchronousSerializationKeepsItsCallingThread(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("caller.yml"); Files.writeString(file, "value: initial\n");
        YamlStorage storage = new YamlStorage(file.toFile());
        CallerThreadBean bean = new CallerThreadBean(Thread.currentThread()); storage.getKey("").setRaw("caller", bean);
        assertDoesNotThrow(storage::save);
        assertEquals(1, bean.calls);
    }

    public static final class CallerThreadBean {
        final Thread caller;
        int calls;
        CallerThreadBean(Thread caller) { this.caller = caller; }
        public String getValue() { calls++; assertSame(caller, Thread.currentThread()); return "checked"; }
        public void setValue(String unused) { }
    }

    public static final class RecursiveBean {
        final YamlStorage storage;
        int calls;
        RecursiveBean(YamlStorage storage) { this.storage = storage; }
        public String getValue() {
            // Bound the fixture even if an implementation accidentally queues the nested request.
            if (++calls > 1) return "nested";
            assertThrows(IllegalStateException.class, storage::saveAsync);
            return "checked";
        }
        public void setValue(String unused) { }
    }
    public static final class BrokenBean {
        public String getValue() { throw new IllegalStateException("synthetic YAML value failure"); }
        public void setValue(String unused) { }
    }
    public static final class BlockingBean {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final boolean fail;
        BlockingBean(boolean fail) { this.fail = fail; }
        public String getValue() {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("serialization fixture timed out");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            if (fail) throw new IllegalStateException("synthetic asynchronous YAML value failure");
            return "older payload";
        }
        public void setValue(String unused) { }
    }
}
