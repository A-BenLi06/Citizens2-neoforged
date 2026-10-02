package net.citizensnpcs.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.citizensnpcs.Settings.Setting;

/** Bounded background calls to MineSkin v2 and the Geyser skin API. */
public final class MojangSkinGenerator {
    private MojangSkinGenerator() { }
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Citizens Skin Service");
        thread.setDaemon(true);
        return thread;
    });

    /** Retains the legacy return shape: uuid and texture.value/texture.signature. */
    public static JsonObject generateFromURL(String url, boolean slim) throws InterruptedException, ExecutionException {
        SkinData.httpURI(url);
        String endpoint = Setting.SKIN_GENERATION_ENDPOINT.asString(), key = Setting.SKIN_GENERATION_API_KEY.asString();
        return call(() -> {
            JsonObject body = new JsonObject();
            body.addProperty("url", url);
            body.addProperty("variant", slim ? "slim" : "classic");
            return generated(request(SkinData.httpURI(endpoint), "application/json", body.toString().getBytes(StandardCharsets.UTF_8), key));
        });
    }

    public static JsonObject generateFromPNG(byte[] png, boolean slim) throws InterruptedException, ExecutionException {
        byte[] snapshot = png.clone();
        String endpoint = Setting.SKIN_GENERATION_ENDPOINT.asString(), key = Setting.SKIN_GENERATION_API_KEY.asString();
        return call(() -> {
            SkinData.validatePNG(snapshot);
            String boundary = "Citizens" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(snapshot);
            body.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n"
                    + (slim ? "slim" : "classic") + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return generated(request(SkinData.httpURI(endpoint), "multipart/form-data; boundary=" + boundary, body.toByteArray(), key));
        });
    }

    public static Long getXUIDFromName(String name) throws InterruptedException, ExecutionException {
        URI uri = bedrockURI("xbox/xuid/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"));
        return call(() -> {
            JsonObject json = request(uri, null, null, "");
            return json.has("xuid") ? Long.parseUnsignedLong(json.get("xuid").getAsString()) : null;
        });
    }

    public static GameProfile getFilledGameProfileByXUID(String name, long xuid) throws InterruptedException, ExecutionException {
        URI uri = bedrockURI("skin/" + Long.toUnsignedString(xuid));
        return call(() -> {
            JsonObject json = request(uri, null, null, "");
            String value = json.get("value").getAsString(), signature = json.get("signature").getAsString();
            SkinData.textures(value);
            return new SkinProperty(SkinProperty.TEXTURES_KEY, value, signature).applyProperties(new GameProfile(new UUID(0, xuid), name));
        });
    }

    private static URI bedrockURI(String path) {
        String base = Setting.BEDROCK_SKIN_ENDPOINT.asString();
        return SkinData.httpURI((base.endsWith("/") ? base : base + "/") + path);
    }

    private static <T> T call(Callable<T> task) throws InterruptedException, ExecutionException {
        var future = EXECUTOR.submit(task);
        try { return future.get(); }
        catch (InterruptedException interrupted) { future.cancel(true); throw interrupted; }
    }

    private static JsonObject generated(JsonObject response) throws IOException {
        try {
            if (!response.get("success").getAsBoolean()) throw new IllegalArgumentException();
            JsonObject skin = response.getAsJsonObject("skin"), texture = skin.getAsJsonObject("texture").getAsJsonObject("data");
            String id = skin.get("uuid").getAsString(), signature = texture.get("signature").getAsString();
            SkinData.textures(texture.get("value").getAsString());
            if (id.isBlank() || signature.isBlank()) throw new IllegalArgumentException();
            JsonObject result = new JsonObject();
            result.addProperty("uuid", id);
            result.add("texture", texture.deepCopy());
            return result;
        } catch (RuntimeException invalid) { throw new IOException("Invalid skin-service response", invalid); }
    }

    private static JsonObject request(URI uri, String contentType, byte[] body, String apiKey) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Citizens-NeoForge/2.0");
        connection.setRequestProperty("Accept", "application/json");
        if (!apiKey.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Skin service returned HTTP " + status);
            byte[] bytes;
            try (var input = connection.getInputStream()) { bytes = input.readNBytes(SkinData.MAX_IMAGE_BYTES + 1); }
            if (bytes.length > SkinData.MAX_IMAGE_BYTES) throw new IOException("Skin response is too large");
            try { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException invalid) { throw new IOException("Invalid skin-service JSON", invalid); }
        } finally { connection.disconnect(); }
    }
}
