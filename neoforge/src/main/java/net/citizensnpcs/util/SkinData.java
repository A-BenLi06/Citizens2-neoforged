package net.citizensnpcs.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Parsing shared by stored textures, skin commands and skin-generation responses. */
public final class SkinData {
    public static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private SkinData() { }

    public static JsonObject textures(String encoded) {
        try {
            if (encoded == null || encoded.length() > MAX_IMAGE_BYTES) throw new IllegalArgumentException();
            var root = JsonParser.parseString(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("textures")
                    || !root.getAsJsonObject().get("textures").isJsonObject()) throw new IllegalArgumentException();
            return root.getAsJsonObject().getAsJsonObject("textures");
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid texture data", invalid);
        }
    }

    public static URI textureURL(String encoded) {
        try { return httpURI(textures(encoded).getAsJsonObject("SKIN").get("url").getAsString()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Texture has no valid skin URL", invalid); }
    }

    public static URI httpURI(String value) {
        URI uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Expected an HTTP(S) URL without credentials or fragment");
        return uri;
    }

    /** Check dimensions before decoding pixels; keep the original PNG bytes for upload/export. */
    public static void validatePNG(byte[] bytes) throws IOException {
        if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("Skin image is too large");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid skin image");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!"png".equalsIgnoreCase(reader.getFormatName()) || reader.getWidth(0) != 64
                        || reader.getHeight(0) != 32 && reader.getHeight(0) != 64)
                    throw new IOException("Skin must be a 64x32 or 64x64 PNG");
                if (reader.read(0) == null) throw new IOException("Invalid PNG data");
            } finally { reader.dispose(); }
        }
    }
}
