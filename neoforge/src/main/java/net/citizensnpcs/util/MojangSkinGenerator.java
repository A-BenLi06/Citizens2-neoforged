package net.citizensnpcs.util;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.io.CharStreams;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

import net.citizensnpcs.api.util.Messaging;

/**
 * Uploads skins to mineskin.org and looks up Bedrock (Geyser) skins, so that {@code /npc skin --url} and Floodgate
 * player names keep working.
 * <p>
 * Ported unchanged apart from json-simple, a Bukkit-provided library, being swapped for Gson, which Minecraft already
 * bundles. Return types are therefore {@link JsonObject} rather than {@code JSONObject}.
 */
public class MojangSkinGenerator {
    public static JsonObject generateFromPNG(final byte[] png, boolean slim)
            throws InterruptedException, ExecutionException {
        return EXECUTOR.submit(() -> {
            DataOutputStream out = null;
            InputStreamReader reader = null;
            try {
                URL target = new URI("https://api.mineskin.org/generate/upload" + (slim ? "?model=slim" : "")).toURL();
                HttpURLConnection con = (HttpURLConnection) target.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("User-Agent", "Citizens/2.0");
                con.setRequestProperty("Cache-Control", "no-cache");
                con.setRequestProperty("Content-Type", "multipart/form-data;boundary=*****");
                con.setConnectTimeout(2000);
                con.setReadTimeout(30000);
                out = new DataOutputStream(con.getOutputStream());
                out.writeBytes("--*****\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
                out.writeBytes("Content-Type: image/png\r\n\r\n");
                out.write(png);
                out.writeBytes("\r\n");
                out.writeBytes("--*****\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"name\";\r\n\r\n\r\n");
                if (slim) {
                    out.writeBytes("--*****\r\n");
                    out.writeBytes("Content-Disposition: form-data; name=\"variant\";\r\n\r\n");
                    out.writeBytes("slim\r\n");
                }
                out.writeBytes("--*****--\r\n");
                out.flush();
                out.close();
                reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8);
                String str = CharStreams.toString(reader);
                if (Messaging.isDebugging()) {
                    Messaging.debug(str);
                }
                if (con.getResponseCode() != 200)
                    return null;

                JsonObject data = objectMember(JsonParser.parseString(str), "data");
                con.disconnect();
                return data;
            } finally {
                closeQuietly(out);
                closeQuietly(reader);
            }
        }).get();
    }

    public static JsonObject generateFromURL(final String url, boolean slim)
            throws InterruptedException, ExecutionException {
        return EXECUTOR.submit(() -> {
            DataOutputStream out = null;
            InputStreamReader reader = null;
            try {
                URL target = new URI("https://api.mineskin.org/generate/url").toURL();
                HttpURLConnection con = (HttpURLConnection) target.openConnection();
                con.setRequestMethod("POST");
                con.setDoOutput(true);
                con.setRequestProperty("User-Agent", "Citizens/2.0");
                con.setRequestProperty("Cache-Control", "no-cache");
                con.setRequestProperty("Accept", "application/json");
                con.setRequestProperty("Content-Type", "application/json");
                con.setConnectTimeout(2000);
                con.setReadTimeout(30000);
                out = new DataOutputStream(con.getOutputStream());
                JsonObject req = new JsonObject();
                req.addProperty("url", url);
                req.addProperty("name", "");
                if (slim) {
                    req.addProperty("variant", "slim");
                }
                out.writeBytes(req.toString().replace("\\", ""));
                out.close();
                reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8);
                String str = CharStreams.toString(reader);
                if (Messaging.isDebugging()) {
                    Messaging.debug(str);
                }
                if (con.getResponseCode() != 200)
                    return null;

                JsonObject data = objectMember(JsonParser.parseString(str), "data");
                con.disconnect();
                return data;
            } finally {
                closeQuietly(out);
                closeQuietly(reader);
            }
        }).get();
    }

    public static GameProfile getFilledGameProfileByXUID(String name, long xuid)
            throws InterruptedException, ExecutionException {
        return EXECUTOR.submit(() -> {
            InputStreamReader reader = null;
            try {
                URL target = new URI("https://api.geysermc.org/v2/skin/" + xuid).toURL();
                HttpURLConnection con = (HttpURLConnection) target.openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("User-Agent", "Citizens/2.0");
                con.setRequestProperty("Accept", "application/json");
                con.setConnectTimeout(2000);
                con.setReadTimeout(20000);
                reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8);
                String str = CharStreams.toString(reader);
                if (Messaging.isDebugging()) {
                    Messaging.debug(str);
                }
                if (con.getResponseCode() != 200)
                    return null;

                JsonElement parsed = JsonParser.parseString(str);
                con.disconnect();
                if (!parsed.isJsonObject())
                    return null;
                JsonObject output = parsed.getAsJsonObject();
                String hex = Long.toHexString(xuid);
                GameProfile profile = new GameProfile(
                        UUID.fromString("00000000-0000-0000-" + hex.substring(0, 4) + "-" + hex.substring(4)), name);
                return new SkinProperty(stringMember(output, "texture_id"), stringMember(output, "value"),
                        stringMember(output, "signature")).applyProperties(profile);
            } finally {
                closeQuietly(reader);
            }
        }).get();
    }

    public static Long getXUIDFromName(String name) throws InterruptedException, ExecutionException {
        return EXECUTOR.submit(() -> {
            InputStreamReader reader = null;
            try {
                URL target = new URI("https://api.geysermc.org/v2/xbox/xuid/" + name).toURL();
                HttpURLConnection con = (HttpURLConnection) target.openConnection();
                con.setRequestMethod("GET");
                con.setRequestProperty("User-Agent", "Citizens/2.0");
                con.setRequestProperty("Accept", "application/json");
                con.setConnectTimeout(2000);
                con.setReadTimeout(10000);
                reader = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8);
                String str = CharStreams.toString(reader);
                if (Messaging.isDebugging()) {
                    Messaging.debug(str);
                }
                if (con.getResponseCode() != 200)
                    return null;

                JsonElement parsed = JsonParser.parseString(str);
                con.disconnect();
                if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("xuid"))
                    return null;

                return parsed.getAsJsonObject().get("xuid").getAsLong();
            } finally {
                closeQuietly(reader);
            }
        }).get();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null)
            return;
        try {
            closeable.close();
        } catch (IOException e) {
        }
    }

    private static JsonObject objectMember(JsonElement root, String name) {
        if (root == null || !root.isJsonObject())
            return null;
        JsonElement member = root.getAsJsonObject().get(name);
        return member != null && member.isJsonObject() ? member.getAsJsonObject() : null;
    }

    private static String stringMember(JsonObject object, String name) {
        JsonElement member = object.get(name);
        return member == null || member.isJsonNull() ? null : member.getAsString();
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
}
