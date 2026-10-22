package net.citizensnpcs.util;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Journal wire types are evidence: decoding must not truncate numbers or reinterpret JSON types. */
final class JournalJson {
    private static final ClassValue<Map<String, Type>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Type> computeValue(Class<?> type) {
            if (!type.isRecord()) throw new IllegalArgumentException("Expected a journal record");
            var fields = new LinkedHashMap<String, Type>();
            for (var component : type.getRecordComponents()) fields.put(component.getName(), component.getGenericType());
            return Collections.unmodifiableMap(fields);
        }
    };
    private static final ClassValue<Set<String>> ENUMS = new ClassValue<>() {
        @Override protected Set<String> computeValue(Class<?> type) {
            var names = new HashSet<String>();
            for (Object value : type.getEnumConstants()) names.add(((Enum<?>) value).name());
            return Set.copyOf(names);
        }
    };
    static JsonObject parse(String source) {
        try (var reader = new JsonReader(new StringReader(source))) {
            reader.setLenient(false);
            var parents = new ArrayDeque<JsonElement>();
            JsonElement root = null;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                var token = reader.peek();
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    if (parents.isEmpty()) throw new IllegalArgumentException("Unexpected journal container end");
                    if (token == JsonToken.END_OBJECT) reader.endObject(); else reader.endArray();
                    parents.pop(); continue;
                }
                String name = null;
                if (token == JsonToken.NAME) {
                    name = reader.nextName();
                    if (parents.isEmpty() || !parents.peek().isJsonObject() || parents.peek().getAsJsonObject().has(name))
                        throw new IllegalArgumentException("Duplicate or misplaced journal member: " + name);
                    token = reader.peek();
                }
                JsonElement value = switch (token) {
                    case BEGIN_OBJECT -> { reader.beginObject(); yield new JsonObject(); }
                    case BEGIN_ARRAY -> { reader.beginArray(); yield new JsonArray(); }
                    case STRING -> new JsonPrimitive(reader.nextString());
                    case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
                    case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
                    case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
                    default -> throw new IllegalArgumentException("Unexpected journal token: " + token);
                };
                if (parents.isEmpty()) {
                    if (root != null) throw new IllegalArgumentException("Multiple journal documents");
                    root = value;
                } else if (parents.peek().isJsonArray()) parents.peek().getAsJsonArray().add(value);
                else {
                    if (name == null) throw new IllegalArgumentException("Missing journal member name");
                    parents.peek().getAsJsonObject().add(name, value);
                }
                if (value.isJsonObject() || value.isJsonArray()) parents.push(value);
            }
            if (!parents.isEmpty() || root == null || !root.isJsonObject()) throw new IllegalArgumentException("Expected a complete journal object");
            return root.getAsJsonObject();
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid journal JSON", invalid); }
    }
    static int integer(JsonElement value) { return number(value).intValueExact(); }
    static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Expected a journal string");
        return value.getAsString();
    }
    static <T> T decode(Gson codec, JsonObject payload, Class<T> type) {
        validate(payload, type); return codec.fromJson(payload, type);
    }
    private static BigDecimal number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Expected a journal number");
        return value.getAsBigDecimal();
    }
    private static void validate(JsonElement value, Type expected) {
        if (value == null) throw new IllegalArgumentException("Missing journal value");
        if (value.isJsonNull()) {
            if (expected instanceof Class<?> type && type.isPrimitive()) throw new IllegalArgumentException("Null journal primitive");
            return;
        }
        if (expected instanceof ParameterizedType list && list.getRawType() == List.class) {
            if (!value.isJsonArray()) throw new IllegalArgumentException("Expected a journal list");
            for (var element : value.getAsJsonArray()) validate(element, list.getActualTypeArguments()[0]);
            return;
        }
        if (!(expected instanceof Class<?> type)) throw new IllegalArgumentException("Unsupported journal type: " + expected);
        if (type.isRecord()) {
            var fields = FIELDS.get(type);
            if (!value.isJsonObject() || !value.getAsJsonObject().keySet().equals(fields.keySet()))
                throw new IllegalArgumentException("Journal fields differ from " + type.getSimpleName());
            for (var field : fields.entrySet()) {
                try { validate(value.getAsJsonObject().get(field.getKey()), field.getValue()); }
                catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid journal field " + type.getSimpleName() + "." + field.getKey(), invalid); }
            }
        } else if (type == int.class || type == Integer.class) integer(value);
        else if (type == long.class || type == Long.class) number(value).longValueExact();
        else if (type == boolean.class || type == Boolean.class) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected a journal boolean");
        } else if (type == String.class || type == UUID.class) string(value);
        else if (type.isEnum()) {
            if (!ENUMS.get(type).contains(string(value))) throw new IllegalArgumentException("Unknown journal " + type.getSimpleName());
        } else throw new IllegalArgumentException("Unsupported journal field type: " + type);
    }
    private JournalJson() { }
}
