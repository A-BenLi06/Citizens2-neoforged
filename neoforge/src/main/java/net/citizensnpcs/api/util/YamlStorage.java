package net.citizensnpcs.api.util;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

public class YamlStorage implements Storage {
    private final Map<String, Object> data = new LinkedHashMap<>();
    private final File file;
    private final String header;
    private final boolean transformLists;

    public YamlStorage(File file) {
        this(file, null);
    }

    public YamlStorage(File file, String header) {
        this(file, header, true);
    }

    public YamlStorage(File file, String header, boolean transformLists) {
        this.file = file;
        this.header = header;
        this.transformLists = transformLists;
        if (!file.exists()) {
            try {
                Files.createDirectories(file.toPath().toAbsolutePath().getParent());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create storage directory for " + file, e);
            }
            save();
        }
    }

    /**
     * Keys are stringified rather than cast. SnakeYAML types a scalar key by its shape, so a hand-edited
     * {@code 0: value} arrives as an Integer key and a blind cast would fail the whole file with a
     * {@link ClassCastException} that names no path. Bukkit's YamlConfiguration, which upstream reads through, coerces
     * keys to strings for the same reason.
     */
    private Object deepCopy(Object source) {
        return deepCopy(source, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private Object deepCopy(Object source, Set<Object> ancestors) {
        if (!(source instanceof Map<?, ?>) && !(source instanceof Collection<?>)
                && (source == null || !source.getClass().isArray())) return source;
        if (!ancestors.add(source)) throw new IllegalArgumentException("Recursive YAML collections are not supported");
        try {
            if (source instanceof Map) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) source).entrySet()) {
                    String key = String.valueOf(e.getKey());
                    if (result.containsKey(key)) throw new IllegalArgumentException("Conflicting YAML keys: " + key);
                    result.put(key, deepCopy(e.getValue(), ancestors));
                }
                return result;
            }
            if (source instanceof Collection<?> collection) {
                Collection<Object> result = source instanceof Set<?> ? new LinkedHashSet<>() : new ArrayList<>();
                for (Object item : collection) {
                    result.add(deepCopy(item, ancestors));
                }
                return result;
            }
            int length = java.lang.reflect.Array.getLength(source);
            Object result = java.lang.reflect.Array.newInstance(source.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) java.lang.reflect.Array.set(result, i, deepCopy(java.lang.reflect.Array.get(source, i), ancestors));
            return result;
        } finally {
            ancestors.remove(source);
        }
    }

    @SuppressWarnings("unchecked")
    private void doSave(boolean async) {
        Path target;
        try { target = resolveTarget(); }
        catch (IOException failure) { throw new UncheckedIOException("Could not resolve storage file " + file, failure); }
        requireNotWriting(target);
        Map<String, Object> toSave = (Map<String, Object>) deepCopy(data);
        if (transformLists) {
            transformMapsToLists(toSave);
        }
        CompletableFuture<Void> queued = new CompletableFuture<>();
        CompletableFuture<Void> previous;
        synchronized (PENDING_WRITES) {
            previous = PENDING_WRITES.getOrDefault(target, CompletableFuture.completedFuture(null));
            PENDING_WRITES.put(target, queued);
        }
        queued.whenComplete((unused, failure) -> {
            synchronized (PENDING_WRITES) { PENDING_WRITES.remove(target, queued); }
            if (async && failure != null) {
                Messaging.severe("Could not save", file);
                (failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure).printStackTrace();
            }
        });
        Runnable task = () -> {
            Set<Path> active = ACTIVE_WRITES.get(); active.add(target);
            Throwable failed = null;
            try { writeSnapshot(target, toSave); }
            catch (IOException failure) { failed = new UncheckedIOException("Could not save " + file, failure); }
            catch (RuntimeException | Error failure) { failed = failure; }
            finally {
                active.remove(target);
                if (active.isEmpty()) ACTIVE_WRITES.remove();
            }
            if (failed == null) queued.complete(null);
            else queued.completeExceptionally(failed);
        };
        // A failed write must not poison retries. Synchronous serialization keeps the caller's thread after waiting.
        if (async) {
            try {
                previous.handle((unused, failure) -> null).thenRunAsync(task, ForkJoinPool.commonPool())
                        .whenComplete((unused, failure) -> { if (failure != null) queued.completeExceptionally(failure); });
            } catch (RuntimeException | Error failure) {
                queued.completeExceptionally(failure); throw failure;
            }
        } else {
            previous.handle((unused, failure) -> null).join();
            task.run();
        }
        if (!async) {
            try { queued.join(); }
            catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw failure;
            }
        }
    }

    private static void requireNotWriting(Path target) {
        if (ACTIVE_WRITES.get().contains(target)) throw new IllegalStateException("Cannot recursively access YAML storage while writing " + target);
    }

    private Path resolveTarget() throws IOException {
        Path target = file.toPath().toAbsolutePath().normalize();
        boolean exists;
        try { Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); exists = true; }
        catch (NoSuchFileException absent) { exists = false; }
        // Follow existing links, but do not replace a dangling link with a regular file.
        return exists ? target.toRealPath() : target.getParent().toRealPath().resolve(target.getFileName());
    }

    private void writeSnapshot(Path target, Map<String, Object> toSave) throws IOException {
        BasicFileAttributes attributes;
        try { attributes = Files.readAttributes(target, BasicFileAttributes.class); }
        catch (NoSuchFileException absent) { attributes = null; }
        if (attributes != null && !attributes.isRegularFile()) throw new AccessDeniedException(target.toString(), null, "Storage destination is not a regular file");
        DosFileAttributeView dosView = attributes == null ? null : Files.getFileAttributeView(target, DosFileAttributeView.class);
        DosFileAttributes dos = dosView == null ? null : dosView.readAttributes();
        if (dos != null && dos.isReadOnly()) throw new AccessDeniedException(target.toString(), null, "Storage destination is read-only");
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".tmp-", ".tmp");
        Throwable failed = null;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                    Writer writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                writeYaml(toSave, writer);
                writer.flush(); channel.force(true);
            }
            if (attributes != null) {
                PosixFileAttributeView posix = Files.getFileAttributeView(target, PosixFileAttributeView.class);
                if (posix != null) Files.setPosixFilePermissions(temporary, posix.readAttributes().permissions());
                AclFileAttributeView acl = Files.getFileAttributeView(target, AclFileAttributeView.class);
                if (acl != null) Files.getFileAttributeView(temporary, AclFileAttributeView.class).setAcl(acl.getAcl());
                if (dos != null) {
                    DosFileAttributeView temporaryDos = Files.getFileAttributeView(temporary, DosFileAttributeView.class);
                    temporaryDos.setHidden(dos.isHidden()); temporaryDos.setSystem(dos.isSystem());
                }
            }
            // Never fall back to deleting or truncating the valid destination when atomic replacement is unsupported.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error failure) {
            failed = failure; throw failure;
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) {
                if (failed != null) failed.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private void writeYaml(Map<String, Object> toSave, Writer writer) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);

        if (header != null && !header.isEmpty()) {
            for (String line : header.split("\n", -1)) writer.write(line.isEmpty() ? "#\n" : "# " + line + "\n");
        }
        yaml.dump(toSave, writer);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof YamlStorage && Objects.equals(file, ((YamlStorage) obj).file);
    }

    @Override
    public DataKey getKey(String root) {
        return new MemoryDataKey(data).getRelative(root);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(file);
    }

    private boolean isSequentialIntKeys(Map<String, ?> map) {
        if (map.isEmpty())
            return false;
        int i = 0;
        while (map.containsKey(String.valueOf(i)))
            i++;
        return i == map.size();
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean load() {
        try {
            Path target = resolveTarget();
            requireNotWriting(target);
            CompletableFuture<Void> pending;
            synchronized (PENDING_WRITES) { pending = PENDING_WRITES.get(target); }
            // Reload reads the last successfully published file after prior requests have completed, including failures.
            if (pending != null) pending.handle((unused, failure) -> null).join();
            try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
                LoaderOptions options = new LoaderOptions();
                options.setCodePointLimit(64 * 1024 * 1024);
                options.setAllowDuplicateKeys(false);
                Object loaded = new Yaml(options).load(reader);
                if (loaded != null && !(loaded instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected a YAML mapping");
                Map<String, Object> replacement = loaded == null ? new LinkedHashMap<>() : (Map<String, Object>) deepCopy(loaded);
                if (transformLists) {
                    transformListsToMaps(replacement);
                }
                data.clear();
                data.putAll(replacement);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public void save() {
        doSave(false);
    }

    @Override
    public void saveAsync() {
        doSave(true);
    }

    @Override
    public String toString() {
        return "YamlStorage{file=" + file + "}";
    }

    @SuppressWarnings("unchecked")
    private void transformListsToMaps(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Collection) {
                Map<String, Object> indexed = new LinkedHashMap<>();
                int i = 0;
                for (Object item : (Collection<?>) value) {
                    Object copied = deepCopy(item);
                    indexed.put(String.valueOf(i++), copied);
                    if (copied instanceof Map) {
                        transformListsToMaps((Map<String, Object>) copied);
                    }
                }
                entry.setValue(indexed);
            } else if (value instanceof Map) {
                Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) value);
                entry.setValue(mutable);
                transformListsToMaps(mutable);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void transformMapsToLists(Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : new ArrayList<>(map.entrySet())) {
            if (!(entry.getValue() instanceof Map))
                continue;
            Map<String, Object> child = (Map<String, Object>) entry.getValue();
            transformMapsToLists(child);
            if (!isSequentialIntKeys(child))
                continue;
            List<Object> list = new ArrayList<>(child.size());
            for (int i = 0; i < child.size(); i++) {
                list.add(child.get(String.valueOf(i)));
            }
            entry.setValue(list);
        }
    }

    private static final Map<Path, CompletableFuture<Void>> PENDING_WRITES = new HashMap<>();
    private static final ThreadLocal<Set<Path>> ACTIVE_WRITES = ThreadLocal.withInitial(HashSet::new);
}
