package net.citizensnpcs.api.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the data-only subset of Java serialization used by Bukkit's ItemMeta wrapper. Never loads stream classes. */
final class LegacyBukkitData {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 65536;
    private static final Set<String> CLASSES = Set.of("org.bukkit.util.io.Wrapper",
            "com.google.common.collect.ImmutableMap$SerializedForm", "com.google.common.collect.ImmutableBiMap$SerializedForm",
            "com.google.common.collect.ImmutableList$SerializedForm",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.ArrayList", "java.util.Arrays$ArrayList",
            "java.lang.Number", "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character");
    private final DataInputStream input;
    private final List<Object> handles = new ArrayList<>();
    private int depth;
    private int converted;

    private LegacyBukkitData(byte[] bytes) { input = new DataInputStream(new ByteArrayInputStream(bytes)); }

    static Map<String, Object> read(String encoded) {
        if (encoded == null || encoded.length() > (MAX_BYTES + 2L) / 3 * 4)
            throw invalid("Metadata exceeds the byte limit");
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_BYTES) throw invalid("Metadata exceeds the byte limit");
            LegacyBukkitData reader = new LegacyBukkitData(bytes);
            if (reader.input.readInt() != 0xaced0005) throw invalid("Not a Java serialization stream");
            Object raw = reader.value(reader.input.readUnsignedByte());
            if (reader.input.read() != -1) throw invalid("Trailing metadata stream data");
            if (!(raw instanceof ObjectData object) || !object.type.name.equals("org.bukkit.util.io.Wrapper"))
                throw invalid("Expected Bukkit metadata wrapper");
            Object decoded = reader.convert(raw, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
            if (!(decoded instanceof Map<?, ?> map)) throw invalid("Expected metadata map");
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid("Non-string metadata key");
                result.put(key, entry.getValue());
            }
            return result;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed Bukkit metadata stream", failure);
        }
    }

    private Object value(int token) throws IOException {
        if (++depth > 64) throw invalid("Metadata nesting exceeds the limit");
        try {
            return switch (token) {
                case 0x70 -> null;
                case 0x71 -> reference();
                case 0x73 -> object();
                case 0x74 -> remember(input.readUTF());
                case 0x7c -> remember(longString());
                case 0x75 -> array();
                default -> throw invalid("Unsupported serialization token " + token);
            };
        } finally { depth--; }
    }

    private Object reference() throws IOException {
        int index = input.readInt() - 0x7e0000;
        if (index < 0 || index >= handles.size()) throw invalid("Invalid stream reference");
        return handles.get(index);
    }

    private <T> T remember(T value) {
        if (handles.size() >= MAX_ENTRIES) throw invalid("Too many stream references");
        handles.add(value); return value;
    }

    private Descriptor descriptor() throws IOException {
        if (++depth > 64) throw invalid("Descriptor nesting exceeds the limit");
        try {
            int token = input.readUnsignedByte();
            if (token == 0x70) return null;
            if (token == 0x71) {
                Object ref = reference();
                if (!(ref instanceof Descriptor descriptor)) throw invalid("Invalid class reference");
                return descriptor;
            }
            if (token != 0x72) throw invalid("Unsupported class descriptor");
            String name = input.readUTF(); input.readLong(); // serialVersionUID is data, never a request to load a class.
            if (!CLASSES.contains(name) && !name.matches("\\[(?:[BCDFIJSZ]|L(?:java\\.lang\\.(?:Object|String)|java\\.util\\.Map\\$Entry);)"))
                throw invalid("Unsupported serialized class: " + name);
            Descriptor descriptor = remember(new Descriptor(name));
            descriptor.flags = input.readUnsignedByte();
            if (descriptor.flags != 2 && descriptor.flags != 3) throw invalid("Unsupported class flags");
            int fields = input.readUnsignedShort();
            if (fields > 64) throw invalid("Too many serialized fields");
            for (int i = 0; i < fields; i++) {
                char type = (char) input.readUnsignedByte(); String field = input.readUTF();
                if (type == 'L' || type == '[') {
                    if (!(value(input.readUnsignedByte()) instanceof String)) throw invalid("Invalid field signature");
                } else if ("BCDFIJSZ".indexOf(type) < 0) throw invalid("Invalid field type");
                if (descriptor.fields.put(field, type) != null) throw invalid("Duplicate serialized field");
            }
            if (input.readUnsignedByte() != 0x78) throw invalid("Unsupported class annotation");
            descriptor.parent = descriptor();
            return descriptor;
        } finally { depth--; }
    }

    private ObjectData object() throws IOException {
        Descriptor descriptor = descriptor();
        if (descriptor == null || descriptor.name.startsWith("[")) throw invalid("Missing object class");
        ObjectData object = remember(new ObjectData(descriptor));
        List<Descriptor> lineage = new ArrayList<>();
        for (Descriptor current = descriptor; current != null; current = current.parent) {
            if (lineage.contains(current) || lineage.size() >= 64) throw invalid("Cyclic class hierarchy");
            lineage.add(current);
        }
        Collections.reverse(lineage);
        for (Descriptor current : lineage) {
            Layer layer = new Layer();
            if (object.layers.putIfAbsent(current.name, layer) != null) throw invalid("Duplicate class in serialized hierarchy");
            for (var field : current.fields.entrySet()) layer.fields.put(field.getKey(), primitive(field.getValue()));
            if ((current.flags & 1) != 0) {
                int token;
                while ((token = input.readUnsignedByte()) != 0x78) {
                    if (layer.custom.size() >= MAX_ENTRIES) throw invalid("Too much custom stream data");
                    if (token == 0x77 || token == 0x7a) {
                        int count = token == 0x77 ? input.readUnsignedByte() : input.readInt();
                        if (count < 0 || count > input.available()) throw invalid("Invalid block length");
                        layer.custom.add(input.readNBytes(count));
                    } else layer.custom.add(value(token));
                }
            }
        }
        return object;
    }

    private Object primitive(char type) throws IOException {
        return switch (type) {
            case 'B' -> input.readByte(); case 'C' -> input.readChar(); case 'D' -> input.readDouble();
            case 'F' -> input.readFloat(); case 'I' -> input.readInt(); case 'J' -> input.readLong();
            case 'S' -> input.readShort(); case 'Z' -> input.readBoolean();
            case 'L', '[' -> value(input.readUnsignedByte());
            default -> throw invalid("Unsupported primitive");
        };
    }

    private List<Object> array() throws IOException {
        Descriptor descriptor = descriptor();
        if (descriptor == null || !descriptor.name.startsWith("[") || descriptor.parent != null
                || !descriptor.fields.isEmpty()) throw invalid("Invalid array class");
        int size = length(input.readInt()); List<Object> array = remember(new ArrayList<>(size));
        char type = descriptor.name.charAt(1);
        for (int i = 0; i < size; i++) array.add(primitive(type));
        return array;
    }

    private String longString() throws IOException {
        long size = input.readLong();
        if (size < 0 || size > input.available()) throw invalid("Invalid long string length");
        byte[] bytes = input.readNBytes((int) size);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < bytes.length;) {
            int a = bytes[i++] & 255;
            if (a < 128) { result.append((char) a); continue; }
            int extra = (a & 0xe0) == 0xc0 ? 1 : (a & 0xf0) == 0xe0 ? 2 : -1;
            if (extra < 0 || i + extra > bytes.length) throw invalid("Invalid modified UTF");
            int code = a & (extra == 1 ? 31 : 15);
            for (int j = 0; j < extra; j++) {
                int b = bytes[i++] & 255;
                if ((b & 0xc0) != 0x80) throw invalid("Invalid modified UTF continuation");
                code = (code << 6) | (b & 63);
            }
            result.append((char) code);
        }
        return result.toString();
    }

    private Object convert(Object raw, Set<Object> visiting, int depth) throws IOException {
        if (depth > 64 || ++converted > MAX_ENTRIES) throw invalid("Metadata graph exceeds the limit");
        if (raw == null || raw instanceof String || raw instanceof Number || raw instanceof Boolean || raw instanceof Character)
            return raw;
        if (!visiting.add(raw)) throw invalid("Cyclic metadata graph");
        try {
            if (raw instanceof List<?> list) {
                List<Object> result = new ArrayList<>();
                for (Object child : list) result.add(convert(child, visiting, depth + 1));
                return result;
            }
            if (!(raw instanceof ObjectData object)) throw invalid("Unexpected metadata node");
            Layer layer = object.layers.get(object.type.name);
            Object result;
            switch (object.type.name) {
                case "org.bukkit.util.io.Wrapper" -> {
                    fields(object, "map"); result = layer.fields.get("map");
                }
                case "com.google.common.collect.ImmutableMap$SerializedForm", "com.google.common.collect.ImmutableBiMap$SerializedForm" -> {
                    Layer mapLayer = object.layers.get("com.google.common.collect.ImmutableMap$SerializedForm");
                    boolean bimap = object.type.name.equals("com.google.common.collect.ImmutableBiMap$SerializedForm");
                    if (mapLayer == null || !mapLayer.fields.keySet().equals(Set.of("keys", "values"))
                            || !mapLayer.custom.isEmpty() || object.layers.size() != (bimap ? 2 : 1)
                            || bimap && (!layer.fields.isEmpty() || !layer.custom.isEmpty())) throw invalid("Invalid immutable map fields");
                    List<?> keys = list(mapLayer.fields.get("keys")); List<?> values = list(mapLayer.fields.get("values"));
                    if (keys.size() != values.size()) throw invalid("Mismatched map arrays");
                    return map(keys, values, visiting, depth);
                }
                case "com.google.common.collect.ImmutableList$SerializedForm" -> {
                    fields(object, "elements"); result = list(layer.fields.get("elements"));
                }
                case "java.util.Arrays$ArrayList" -> { fields(object, "a"); result = list(layer.fields.get("a")); }
                case "java.util.ArrayList" -> {
                    fieldsWithCustom(object, Set.of("size"));
                    Custom custom = custom(layer); int size = length(custom.bytes.readInt());
                    if (!Integer.valueOf(size).equals(layer.fields.get("size")) || custom.bytes.available() != 0
                            || custom.values.size() != size) throw invalid("Invalid list size");
                    result = custom.values;
                }
                case "java.util.HashMap", "java.util.LinkedHashMap" -> {
                    Layer mapLayer = object.layers.get("java.util.HashMap");
                    if (mapLayer == null || !mapLayer.fields.keySet().equals(Set.of("loadFactor", "threshold"))
                            || object.layers.size() != (object.type.name.equals("java.util.HashMap") ? 1 : 2))
                        throw invalid("Invalid map fields");
                    if (object.layers.size() == 2 && (!layer.fields.keySet().equals(Set.of("accessOrder")) || !layer.custom.isEmpty()))
                        throw invalid("Invalid linked map fields");
                    Custom custom = custom(mapLayer); custom.bytes.readInt(); int size = length(custom.bytes.readInt());
                    if (custom.bytes.available() != 0 || custom.values.size() != size * 2) throw invalid("Invalid map size");
                    List<Object> keys = new ArrayList<>(), values = new ArrayList<>();
                    for (int i = 0; i < size; i++) { keys.add(custom.values.get(i * 2)); values.add(custom.values.get(i * 2 + 1)); }
                    return map(keys, values, visiting, depth);
                }
                default -> {
                    if (!object.type.name.startsWith("java.lang.")) throw invalid("Unsupported data object");
                    if (!layer.fields.keySet().equals(Set.of("value")) || !layer.custom.isEmpty()
                            || object.layers.entrySet().stream().anyMatch(entry -> entry.getValue() != layer
                            && (!entry.getKey().equals("java.lang.Number") || !entry.getValue().fields.isEmpty()
                                    || !entry.getValue().custom.isEmpty()))) throw invalid("Invalid boxed primitive");
                    result = layer.fields.get("value");
                    if (!(result instanceof Number || result instanceof Boolean || result instanceof Character))
                        throw invalid("Invalid primitive value");
                }
            }
            return convert(result, visiting, depth + 1);
        } finally { visiting.remove(raw); }
    }

    private Map<Object, Object> map(List<?> keys, List<?> values, Set<Object> visiting, int depth) throws IOException {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            Object key = convert(keys.get(i), visiting, depth + 1);
            if (!(key instanceof String) || result.containsKey(key)) throw invalid("Invalid or duplicate map key");
            result.put(key, convert(values.get(i), visiting, depth + 1));
        }
        return result;
    }

    private static void fields(ObjectData object, String... names) {
        fieldsWithCustom(object, Set.of(names));
        if (!object.layers.get(object.type.name).custom.isEmpty()) throw invalid("Unexpected custom data");
    }

    private static void fieldsWithCustom(ObjectData object, Set<String> names) {
        if (object.layers.size() != 1 || !object.layers.get(object.type.name).fields.keySet().equals(names))
            throw invalid("Unexpected serialized fields");
    }

    private static Custom custom(Layer layer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); List<Object> values = new ArrayList<>();
        boolean objects = false;
        for (Object part : layer.custom) {
            if (part instanceof byte[] block) {
                if (objects) throw invalid("Unexpected interleaved custom bytes");
                bytes.write(block);
            } else { objects = true; values.add(part); }
        }
        return new Custom(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), values);
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw invalid("Expected serialized array");
        return list;
    }
    private static int length(int value) {
        if (value < 0 || value > MAX_ENTRIES) throw invalid("Invalid collection length"); return value;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static final class Descriptor {
        final String name; final Map<String, Character> fields = new LinkedHashMap<>(); int flags; Descriptor parent;
        Descriptor(String name) { this.name = name; }
    }
    private static final class ObjectData {
        final Descriptor type; final Map<String, Layer> layers = new LinkedHashMap<>();
        ObjectData(Descriptor type) { this.type = type; }
    }
    private static final class Layer { final Map<String, Object> fields = new LinkedHashMap<>(); final List<Object> custom = new ArrayList<>(); }
    private record Custom(DataInputStream bytes, List<Object> values) { }
}
