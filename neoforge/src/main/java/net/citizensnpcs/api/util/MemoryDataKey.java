package net.citizensnpcs.api.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

/**
 * A DataKey implementation that uses raw Map<String, Object> for storage instead of Bukkit's ConfigurationSection. This
 * preserves literal key names containing dots instead of interpreting them as path separators.
 */
public class MemoryDataKey extends DataKey {
    private final String name;
    private final Map<String, Object> root;

    public MemoryDataKey() {
        this(new HashMap<>(), "", "");
    }

    public MemoryDataKey(Map<String, Object> root) {
        this(root, "", "");
    }

    private MemoryDataKey(Map<String, Object> root, String path, String name) {
        super(path);
        this.root = root;
        this.name = name;
    }

    @Override
    public MemoryDataKey copy() {
        return new MemoryDataKey(deepCopyMap(getCurrentSection()), "", "");
    }

    @Override
    public MemoryDataKey copyWithRoot() {
        // Preserve literal key segments; the display path cannot distinguish dots inside a key from separators.
        return new MemoryDataKey(deepCopyMap(root), path, name);
    }

    @Override
    public void writeAtomically(Consumer<DataKey> writer) {
        // The outer transaction takes the full snapshot once. Nested writers fork only accessed branches and commit
        // detached changes into that unpublished document; a failed inner writer leaves its parent unchanged.
        StagedMap<String> staged = new StagedMap<>(root instanceof StagedMap<?> ? root : deepCopyMap(root));
        writer.accept(new MemoryDataKey(staged, path, name));
        if (root instanceof StagedMap<?>) {
            staged.prepare().run();
        } else {
            Map<String, Object> published = deepCopyMap(staged);
            root.clear();
            root.putAll(published);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source instanceof StagedMap<?> staged) return (Map<String, Object>) staged.detached();
        Map<String, Object> result = Maps.newHashMapWithExpectedSize(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof StagedMap<?> staged) return staged.detached();
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new java.util.LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(key, copyValue(entry)));
            return copy;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> copy = new java.util.ArrayList<>(list.size());
            list.forEach(entry -> copy.add(copyValue(entry)));
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            Object copy = java.lang.reflect.Array.newInstance(value.getClass().getComponentType(), length);
            for (int i = 0; i < length; i++) java.lang.reflect.Array.set(copy, i, copyValue(java.lang.reflect.Array.get(value, i)));
            return copy;
        }
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        MemoryDataKey other = (MemoryDataKey) obj;
        return Objects.equals(getDisplayPath(), other.getDisplayPath()) && root == other.root;
    }

    @Override
    public boolean getBoolean(String key) {
        Object value = getRaw(key);
        if (value instanceof Boolean)
            return (Boolean) value;
        if (value != null)
            return Boolean.parseBoolean(value.toString());
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCurrentSection() {
        if (path.isEmpty())
            return root;

        Map<String, Object> current = root;
        String[] segments = path.split("\\" + INTERNAL_SEPARATOR);
        for (String segment : segments) {
            Object next = current.get(segment);
            if (!(next instanceof Map))
                return Collections.emptyMap();
            current = (Map<String, Object>) next;
        }
        return current;
    }

    private String getDisplayPath() {
        return path.replace(INTERNAL_SEPARATOR, '.');
    }

    @Override
    public double getDouble(String key) {
        return getDouble(key, 0);
    }

    @Override
    public double getDouble(String key, double def) {
        Object value = getRaw(key);
        if (value instanceof Number)
            return ((Number) value).doubleValue();
        if (value != null) {
            String raw = value.toString();
            if (!raw.isEmpty()) {
                Double res = Doubles.tryParse(raw);
                return res != null ? res : def;
            }
        }
        return def;
    }

    @Override
    public DataKey getFromRoot(String path) {
        return navigatePath(root, "", path);
    }

    @Override
    public int getInt(String key) {
        return getInt(key, 0);
    }

    @Override
    public int getInt(String key, int def) {
        Object value = getRaw(key);
        if (value instanceof Number)
            return ((Number) value).intValue();
        if (value != null) {
            String raw = value.toString();
            if (!raw.isEmpty()) {
                Integer res = Ints.tryParse(raw);
                return res != null ? res : def;
            }
        }
        return def;
    }

    @Override
    public long getLong(String key) {
        return getLong(key, 0);
    }

    @Override
    public long getLong(String key, long def) {
        Object value = getRaw(key);
        if (value instanceof Number)
            return ((Number) value).longValue();
        if (value != null) {
            String raw = value.toString();
            if (!raw.isEmpty()) {
                Long res = Longs.tryParse(raw);
                return res != null ? res : def;
            }
        }
        return def;
    }

    @Override
    public String getPath() {
        return getDisplayPath();
    }

    @Override
    public Object getRaw(String key) {
        if (key == null || key.isEmpty())
            return getValueAtCurrentPath();
        return navigateToValue(key);
    }

    @Override
    public MemoryDataKey getRelative(String relative) {
        if (relative == null || relative.isEmpty())
            return this;
        return navigatePath(root, path, relative);
    }

    @Override
    public String getString(String key) {
        Object val = getRaw(key);
        if (val != null && !(val instanceof Map))
            return val.toString();
        return "";
    }

    @Override
    public Iterable<DataKey> getSubKeys() {
        Map<String, Object> current = getCurrentSection();
        if (current == null || current.isEmpty())
            return Collections.emptyList();

        return current.keySet().stream().map(k -> {
            String newPath = path.isEmpty() ? k : path + INTERNAL_SEPARATOR + k;
            return new MemoryDataKey(root, newPath, k);
        }).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Object getValueAtCurrentPath() {
        if (path.isEmpty())
            return root;

        Map<String, Object> current = root;
        String[] segments = path.split("\\" + INTERNAL_SEPARATOR);
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            if (!(next instanceof Map))
                return null;
            current = (Map<String, Object>) next;
        }
        return current.get(segments[segments.length - 1]);
    }

    @Override
    public Map<String, Object> getValuesDeep() {
        Map<String, Object> section = getCurrentSection();
        if (section == null)
            return Collections.emptyMap();
        return deepCopyMap(section);
    }

    @Override
    public int hashCode() {
        String displayPath = getDisplayPath();
        return 31 * (displayPath == null ? 0 : displayPath.hashCode()) + System.identityHashCode(root);
    }

    @Override
    public boolean hasSubKeys() {
        Map<String, Object> current = getCurrentSection();
        return current != null && !current.isEmpty();
    }

    @Override
    public boolean keyExists(String key) {
        if (key == null || key.isEmpty())
            return !getCurrentSection().isEmpty();

        return navigateToValue(key) != null;
    }

    @Override
    public String name() {
        return name;
    }

    private MemoryDataKey navigatePath(Map<String, Object> root, String currentInternalPath, String relativePath) {
        // relativePath uses dots as separators - split and add each as a segment
        String[] pathParts = relativePath.split("\\.");
        StringBuilder newInternalPath = new StringBuilder(currentInternalPath);

        for (String part : pathParts) {
            if (newInternalPath.length() > 0) {
                newInternalPath.append(INTERNAL_SEPARATOR);
            }
            newInternalPath.append(part);
        }
        String lastName = pathParts[pathParts.length - 1];
        return new MemoryDataKey(root, newInternalPath.toString(), lastName);
    }

    @SuppressWarnings("unchecked")
    private Object navigateToValue(String relativePath) {
        Map<String, Object> current = getCurrentSection();
        if (current == null)
            return null;

        String[] segments = relativePath.split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            if (!(next instanceof Map))
                return null;
            current = (Map<String, Object>) next;
        }
        return current.get(segments[segments.length - 1]);
    }

    @Override
    public void removeKey(String key) {
        setRaw(key, null);
    }

    @Override
    public void setBoolean(String key, boolean value) {
        setRaw(key, value);
    }

    @Override
    public void setDouble(String key, double value) {
        setRaw(key, value);
    }

    @Override
    public void setInt(String key, int value) {
        setRaw(key, value);
    }

    @Override
    public void setLong(String key, long value) {
        setRaw(key, value);
    }

    @Override
    public void setMap(String key, Map<String, Object> value) {
        setRaw(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setRaw(String key, Object value) {
        String fullInternalPath;
        if (key == null || key.isEmpty()) {
            fullInternalPath = path;
        } else if (path.isEmpty()) {
            fullInternalPath = key.replace('.', INTERNAL_SEPARATOR);
        } else {
            fullInternalPath = path + INTERNAL_SEPARATOR + key.replace('.', INTERNAL_SEPARATOR);
        }
        if (fullInternalPath.isEmpty()) {
            if (value == null) {
                root.clear();
                return;
            }
            if (value instanceof Map) {
                root.clear();
                root.putAll((Map<String, Object>) value);
                return;
            }
            throw new IllegalStateException("Unsupported root value");
        }
        String[] segments = fullInternalPath.split("\\" + INTERNAL_SEPARATOR);
        Map<String, Object> current = root;

        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            if (!(next instanceof Map)) {
                if (value == null)
                    return;
                next = new HashMap<String, Object>();
                current.put(segments[i], next);
            }
            current = (Map<String, Object>) next;
        }
        String lastSegment = segments[segments.length - 1];
        if (value == null) {
            current.remove(lastSegment);
        } else {
            current.put(lastSegment, value);
        }
    }

    @Override
    public void setString(String key, String value) {
        setRaw(key, value);
    }

    @Override
    public String toString() {
        return "MemoryDataKey[" + getDisplayPath() + "]";
    }

    /** A private working map. Reading a mutable value owns a copy before handing it to a writer. */
    private static final class StagedMap<K> extends java.util.AbstractMap<K, Object> {
        private final Map<K, Object> source;
        private final Map<K, Object> values = new java.util.LinkedHashMap<>();
        private final java.util.Set<K> replaced = new java.util.HashSet<>();
        private final java.util.Set<Object> removed = new java.util.HashSet<>();
        private boolean cleared;

        @SuppressWarnings("unchecked")
        StagedMap(Map<?, ?> source) { this.source = (Map<K, Object>) source; }

        @Override
        public boolean containsKey(Object key) {
            return values.containsKey(key) || (!cleared && !removed.contains(key) && source.containsKey(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object get(Object key) {
            if (values.containsKey(key)) return values.get(key);
            if (!containsKey(key)) return null;
            Object original = source.get(key);
            Object value = original instanceof Map<?, ?> map ? new StagedMap<>(map) : copyValue(original);
            values.put((K) key, value);
            // Raw lists and arrays may be mutated without another Map call, so their exposed copies are changes.
            if (value != original && !(value instanceof StagedMap<?>)) replaced.add((K) key);
            return value;
        }

        @Override
        public Object put(K key, Object value) {
            Object previous = get(key);
            assign(key, value);
            return previous;
        }

        private void assign(K key, Object value) {
            values.put(key, value); replaced.add(key); removed.remove(key);
        }

        @Override
        public Object remove(Object key) {
            Object previous = get(key);
            discard(key);
            return previous;
        }

        private void discard(Object key) {
            values.remove(key); replaced.remove(key); removed.add(key);
        }

        @Override
        public void clear() {
            values.clear(); replaced.clear(); removed.clear(); cleared = true;
        }

        private java.util.Set<K> keys() {
            java.util.Set<K> keys = cleared ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(source.keySet());
            keys.removeAll(removed); keys.addAll(values.keySet());
            return keys;
        }

        @Override
        public int size() { return keys().size(); }

        @Override
        public java.util.Set<K> keySet() {
            return new java.util.AbstractSet<>() {
                @Override public int size() { return StagedMap.this.size(); }
                @Override public boolean contains(Object key) { return containsKey(key); }
                @Override public void clear() { StagedMap.this.clear(); }
                @Override public boolean remove(Object key) {
                    if (!containsKey(key)) return false;
                    discard(key); return true;
                }
                @Override public java.util.Iterator<K> iterator() {
                    java.util.Iterator<K> keys = StagedMap.this.keys().iterator();
                    return new java.util.Iterator<>() {
                        private K current;
                        private boolean removable;
                        @Override public boolean hasNext() { return keys.hasNext(); }
                        @Override public K next() { current = keys.next(); removable = true; return current; }
                        @Override public void remove() {
                            if (!removable) throw new IllegalStateException();
                            discard(current); removable = false;
                        }
                    };
                }
            };
        }

        private Object peek(Object key) {
            if (values.containsKey(key)) return values.get(key);
            if (cleared || removed.contains(key)) return null;
            return source instanceof StagedMap<?> parent ? parent.peek(key) : source.get(key);
        }

        private Map<K, Object> detached() {
            Map<K, Object> result = new java.util.LinkedHashMap<>();
            for (K key : keys()) result.put(key, copyValue(peek(key)));
            return result;
        }

        @Override
        public java.util.Set<Entry<K, Object>> entrySet() {
            return new java.util.AbstractSet<>() {
                @Override public int size() { return StagedMap.this.size(); }
                @Override public void clear() { StagedMap.this.clear(); }
                @Override public java.util.Iterator<Entry<K, Object>> iterator() {
                    java.util.Iterator<K> keys = StagedMap.this.keys().iterator();
                    return new java.util.Iterator<>() {
                        private Object current;
                        private boolean removable;
                        @Override public boolean hasNext() { return keys.hasNext(); }
                        @Override public Entry<K, Object> next() {
                            K key = keys.next(); current = key; removable = true;
                            return new SimpleEntry<>(key, get(key)) {
                                @Override public Object setValue(Object value) {
                                    Object previous = put(key, value); super.setValue(value); return previous;
                                }
                            };
                        }
                        @Override public void remove() {
                            if (!removable) throw new IllegalStateException();
                            StagedMap.this.remove(current); removable = false;
                        }
                    };
                }
            };
        }

        private boolean changed() {
            return cleared || !replaced.isEmpty() || !removed.isEmpty()
                    || values.values().stream().anyMatch(value -> value instanceof StagedMap<?> map && map.changed());
        }

        /** Prepare every detached value before applying any change to the parent transaction. */
        @SuppressWarnings("unchecked")
        private Runnable prepare() {
            if (!(source instanceof StagedMap<?>)) throw new IllegalStateException("No parent transaction");
            StagedMap<K> parent = (StagedMap<K>) source;
            Map<K, Object> writes = new java.util.LinkedHashMap<>();
            java.util.List<Runnable> children = new java.util.ArrayList<>();
            for (Map.Entry<K, Object> entry : values.entrySet()) {
                Object value = entry.getValue();
                if (replaced.contains(entry.getKey())) {
                    writes.put(entry.getKey(), copyValue(value));
                } else if (value instanceof StagedMap<?> child && child.changed()) {
                    if (child.source instanceof StagedMap<?>) children.add(child.prepare());
                    else writes.put(entry.getKey(), copyValue(child));
                }
            }
            java.util.Set<Object> deletes = new java.util.HashSet<>(removed);
            boolean clear = cleared;
            return () -> {
                if (clear) parent.clear();
                for (Object key : deletes) parent.discard(key);
                writes.forEach(parent::assign);
                children.forEach(Runnable::run);
            };
        }
    }

    private static final char INTERNAL_SEPARATOR = '\0';
    private static final Splitter INTERNAL_SEPARATOR_SPLITTER = Splitter.on(INTERNAL_SEPARATOR);
}
