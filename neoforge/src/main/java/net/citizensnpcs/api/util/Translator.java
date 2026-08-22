package net.citizensnpcs.api.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * Loads {@code <language>.json} message bundles and formats them with {@link MessageFormat}.
 * <p>
 * Ported from upstream unchanged apart from two things: json-simple (a Bukkit-provided library) is replaced with Gson,
 * which Minecraft already bundles, and the bundled resources moved from the jar root to {@code /citizens/} so that they
 * cannot collide with another mod's resources.
 * <p>
 * Lookup order for a locale: {@code <dataFolder>/<locale>.json}, {@code <dataFolder>/<language>.json}, the bundled
 * {@code /citizens/<language>.json}, then the bundled {@code /citizens/<locale>.json}. Bundled non-English files are
 * copied out to the data folder on first use so that server owners can edit them.
 */
public class Translator {
    private final Map<String, String> baseTranslations = getBaseTranslations();
    private final Locale defaultLocale;
    private final Map<String, MessageFormat> messageFormatCache = new HashMap<>();
    private final Map<String, String> translations;

    private Translator(File resourceDir, Locale locale) {
        this.defaultLocale = locale;
        this.translations = getTranslations(resourceDir, locale);
    }

    private String format(String key, Locale locale, Object... msg) {
        return getFormatter(translate(key, locale)).format(msg);
    }

    private MessageFormat getFormatter(String unreplaced) {
        MessageFormat formatter = messageFormatCache.get(unreplaced);
        if (formatter == null) {
            messageFormatCache.put(unreplaced, formatter = new MessageFormat(unreplaced));
        }
        return formatter;
    }

    private String translate(String key) {
        String res = translations.computeIfAbsent(key, k -> baseTranslations.get(key));
        return res == null ? "?" + key + "?" : res;
    }

    private static class SaveResource implements Runnable {
        private final String fileName;
        private final File rootFolder;

        private SaveResource(File rootFolder, String fileName) {
            this.rootFolder = rootFolder;
            this.fileName = fileName;
        }

        @Override
        public void run() {
            File file = new File(rootFolder, fileName);
            if (file.exists())
                return;

            try (InputStream in = Translator.class.getResourceAsStream(RESOURCE_ROOT + fileName)) {
                if (in == null)
                    return;
                rootFolder.mkdirs();
                File to = File.createTempFile(fileName, null, rootFolder);
                to.deleteOnExit();
                Files.copy(in, to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (!file.exists()) {
                    to.renameTo(file);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static String format(String msg, Object... objects) {
        return INSTANCE.getFormatter(msg).format(objects);
    }

    private static Map<String, String> getBaseTranslations() {
        try (Reader in = new InputStreamReader(Translator.class.getResourceAsStream(RESOURCE_ROOT + "en.json"),
                StandardCharsets.UTF_8)) {
            return readTranslations(in);
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    /**
     * Reads a message bundle, tolerating duplicate keys by keeping the last value.
     * <p>
     * Upstream parses with json-simple, which silently overwrites on a repeated key — and upstream's own {@code
     * en.json} does repeat a couple. Gson's {@code fromJson} into a Map is strict and throws instead, which would take
     * the whole mod down over a cosmetic duplicate, so the stream is read token by token here.
     */
    private static Map<String, String> readTranslations(Reader in) throws IOException {
        Map<String, String> out = new HashMap<>();
        try (JsonReader reader = new JsonReader(in)) {
            reader.setLenient(true);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull();
                    continue;
                }
                out.put(key, reader.nextString());
            }
            reader.endObject();
        }
        return out;
    }

    private static Charset getCharset(String fileName) {
        return JAPANESE_PATTERN.matcher(fileName).find() && Charset.isSupported("Shift-JIS")
                ? Charset.forName("Shift-JIS")
                : StandardCharsets.UTF_8;
    }

    private static Map<String, String> getTranslations(File resourceDir, Locale locale) {
        InputStream is = null;
        Charset charset = getCharset(locale.toString() + ".json");
        File exact = new File(resourceDir, locale.toString() + ".json");
        File language = new File(resourceDir, locale.getLanguage() + ".json");
        try {
            if (exact.exists()) {
                is = new FileInputStream(exact);
            } else if (language.exists()) {
                is = new FileInputStream(language);
            } else {
                is = Translator.class.getResourceAsStream(RESOURCE_ROOT + locale.getLanguage() + ".json");
                if (is != null) {
                    if (!locale.getLanguage().equals("en")) {
                        new Thread(new SaveResource(resourceDir, locale.getLanguage() + ".json")).start();
                    }
                } else {
                    is = Translator.class.getResourceAsStream(RESOURCE_ROOT + locale + ".json");
                    if (is != null && !locale.getLanguage().equals("en")) {
                        new Thread(new SaveResource(resourceDir, locale + ".json")).start();
                    }
                }
            }
            if (is == null)
                return new HashMap<>();

            try (Reader in = new InputStreamReader(is, charset)) {
                return readTranslations(in);
            }
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public static void setInstance(File dataFolder, Locale preferredLocale) {
        INSTANCE = new Translator(dataFolder, preferredLocale);
    }

    private static String translate(String key, Locale preferredLocale, Object... msg) {
        return msg.length == 0 ? INSTANCE.translate(key) : INSTANCE.format(key, preferredLocale, msg);
    }

    public static String translate(String key, Object... msg) {
        if (INSTANCE == null)
            return "?" + key + "?";
        return translate(key, INSTANCE.defaultLocale, msg);
    }

    private static Translator INSTANCE;
    private static final Pattern JAPANESE_PATTERN = Pattern.compile(".*?ja(_jp)?\\.json", Pattern.CASE_INSENSITIVE);
    private static final String RESOURCE_ROOT = "/citizens/";
}
