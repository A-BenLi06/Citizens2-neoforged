package net.citizensnpcs.api.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Message formatting and delivery.
 * <p>
 * Upstream routes everything through Adventure/MiniMessage and Bukkit's {@code CommandSender}. Here, Adventure is
 * replaced by {@link TextParser} (which understands the subset of the tag syntax Citizens emits) and the recipient type
 * becomes {@link CommandSourceStack}. The internal pipeline is otherwise unchanged: legacy {@code &}/{@code §} codes are
 * normalised to {@code <tag>} form by {@link #convertLegacyCodes}, then {@code [[highlight]]} and {@code {{error}}}
 * markers are expanded by {@link #prettify}, and the result is parsed into a Component at send time.
 */
public class Messaging {
    private static class DebugFormatter extends Formatter {
        private final SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ");

        @Override
        public String format(LogRecord rec) {
            Throwable exception = rec.getThrown();
            String out = this.date.format(rec.getMillis());
            out += "[" + rec.getLevel().getName().toUpperCase(Locale.ROOT) + "] ";
            out += rec.getMessage() + '\n';
            if (exception != null) {
                StringWriter writer = new StringWriter();
                exception.printStackTrace(new PrintWriter(writer));
                return out + writer;
            }
            return out;
        }
    }

    public static void configure(File debugFile, boolean debug, boolean resetFormattingOnColorChange,
            String messageColour, String highlightColour, String errorColour) {
        RESET_FORMATTING_ON_COLOR_CHANGE = resetFormattingOnColorChange;
        DEBUG = debug;
        MESSAGE_COLOUR = messageColour.replace("<a>", "<green>");
        HIGHLIGHT_COLOUR = highlightColour.replace("<e>", "<yellow>");
        if (HIGHLIGHT_COLOUR.equals("yellow")) {
            HIGHLIGHT_COLOUR = "<yellow>";
        }
        ERROR_COLOUR = errorColour.replace("<c>", "<red>");

        if (debugFile != null) {
            DEBUG_LOGGER = Logger.getLogger("CitizensDebug");
            try {
                FileHandler fh = new FileHandler(debugFile.getAbsolutePath(), true);
                fh.setFormatter(new DebugFormatter());
                DEBUG_LOGGER.setUseParentHandlers(false);
                DEBUG_LOGGER.addHandler(fh);
            } catch (SecurityException | IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Normalises the several colour syntaxes Citizens accepts into a single {@code <tag>} form: {@code &x} becomes
     * {@code §x}, {@code §x&#rrggbb} spellings become {@code <#rrggbb>}, and {@code §x}/{@code <x>} single-character
     * codes become named tags.
     */
    public static String convertLegacyCodes(String message) {
        if (message == null)
            return null;
        message = translateAlternateColorCodes('&', message);

        Matcher m = HEX_MATCHER.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "<#$1$2$3$4$5$6>");
        }
        m.appendTail(sb);

        m = HEX_CODE_MATCHER.matcher(sb.toString());
        sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "<$1>");
        }
        m.appendTail(sb);

        m = LEGACY_COLORCODE_MATCHER.matcher(sb.toString());
        sb = new StringBuffer();
        while (m.find()) {
            String code = m.group(1) == null ? m.group(2) : m.group(1);
            String replacement = COLORCODE_CONVERTER.get(code.toLowerCase(Locale.ROOT));
            m.appendReplacement(sb, replacement == null ? Matcher.quoteReplacement(m.group()) : replacement);
        }
        m.appendTail(sb);

        if (!RESET_FORMATTING_ON_COLOR_CHANGE)
            return sb.toString();
        return COLOUR_TAG_MATCHER.matcher(sb.toString()).replaceAll("$0<csr>");
    }

    public static void debug(Object... msg) {
        if (isDebugging()) {
            if (DEBUG_LOGGER != null) {
                DEBUG_LOGGER.log(Level.INFO, "[Citizens] " + SPACE.join(msg));
            } else {
                LOGGER.info("{}", SPACE.join(msg));
            }
        }
    }

    public static void idebug(Supplier<String> msg) {
        if (isDebugging()) {
            debug(msg.get());
        }
    }

    public static boolean isDebugging() {
        return DEBUG;
    }

    public static void log(Object... msg) {
        LOGGER.info("{}", SPACE.join(msg));
    }

    public static void logTr(String key, Object... msg) {
        LOGGER.info("{}", Translator.translate(key, msg));
    }

    /** @return {@code raw} rendered as a Minecraft Component */
    public static Component minecraftComponentFromRawMessage(String raw) {
        return TextParser.parse(convertLegacyCodes(raw));
    }

    /** @return {@code raw} rendered to a legacy {@code §} string */
    public static String parseComponents(String raw) {
        return TextParser.toLegacy(convertLegacyCodes(raw));
    }

    public static List<String> parseComponentsList(String raw) {
        return CHAT_NEWLINE_SPLITTER.splitToStream(raw).map(Messaging::parseComponents).collect(Collectors.toList());
    }

    private static String prettify(String message) {
        String trimmed = message.trim();
        String messageColour = MESSAGE_COLOUR;
        String parsed = convertLegacyCodes(trimmed);
        if (!parsed.isEmpty()) {
            if (parsed.charAt(0) == ChatFormatting.PREFIX_CODE) {
                ChatFormatting test = parsed.length() > 1 ? ChatFormatting.getByCode(parsed.charAt(1)) : null;
                if (test == null) {
                    message = messageColour + message;
                } else {
                    messageColour = "" + ChatFormatting.PREFIX_CODE + test.getChar();
                }
            } else {
                message = messageColour + message;
            }
        }
        message = CHAT_NEWLINE.matcher(message).replaceAll("<reset><br>]]");
        message = HIGHLIGHT_MATCHER.matcher(message).replaceAll(Matcher.quoteReplacement(HIGHLIGHT_COLOUR));
        message = ERROR_MATCHER.matcher(message).replaceAll(Matcher.quoteReplacement(ERROR_COLOUR));
        return message.replace("]]", MESSAGE_COLOUR);
    }

    public static void send(CommandSourceStack sender, Object... msg) {
        sendMessageTo(sender, SPACE.join(msg), true);
    }

    public static void sendColorless(CommandSourceStack sender, Object... msg) {
        sendMessageTo(sender, SPACE.join(msg), false);
    }

    public static void sendError(CommandSourceStack sender, Object... msg) {
        send(sender, ERROR_COLOUR + SPACE.join(msg));
    }

    public static void sendErrorTr(CommandSourceStack sender, String key, Object... msg) {
        send(sender, ERROR_COLOUR + Translator.translate(key, msg));
    }

    private static void sendMessageTo(CommandSourceStack sender, String rawMessage, boolean messageColor) {
        for (String message : CHAT_NEWLINE_SPLITTER.split(rawMessage)) {
            if (messageColor) {
                message = prettify(message);
            }
            sender.sendSystemMessage(TextParser.parse(convertLegacyCodes(message)));
        }
    }

    public static void sendTr(CommandSourceStack sender, String key, Object... msg) {
        sendMessageTo(sender, Translator.translate(key, msg), true);
    }

    public static void sendTrColorless(CommandSourceStack sender, String key, Object... msg) {
        sendMessageTo(sender, Translator.translate(key, msg), false);
    }

    /**
     * Sends a message with the NPC's placeholders expanded.
     *
     * @param recipient
     *            the entity to message; non-players are ignored, as they have nowhere to display it
     */
    public static void sendWithNPC(Entity recipient, Object msg, NPC npc) {
        sendToEntity(recipient, msg, npc, true);
    }

    public static void sendWithNPCColorless(Entity recipient, Object msg, NPC npc) {
        sendToEntity(recipient, msg, npc, false);
    }

    private static void sendToEntity(Entity recipient, Object msg, NPC npc, boolean messageColor) {
        if (!(recipient instanceof ServerPlayer))
            return;
        ServerPlayer player = (ServerPlayer) recipient;
        String replaced = Placeholders.replace(msg.toString(), player);
        replaced = Placeholders.replace(replaced, player.createCommandSourceStack(), npc);
        for (String message : CHAT_NEWLINE_SPLITTER.split(replaced)) {
            if (messageColor) {
                message = prettify(message);
            }
            player.sendSystemMessage(TextParser.parse(convertLegacyCodes(message)));
        }
    }

    public static void severe(Object... messages) {
        LOGGER.error("{}", SPACE.join(messages));
    }

    public static void severeTr(String key, Object... messages) {
        LOGGER.error("{}", Translator.translate(key, messages));
    }

    public static String stripColor(String raw) {
        return TextParser.strip(convertLegacyCodes(raw));
    }

    public static String tr(String key, Object... messages) {
        return prettify(Translator.translate(key, messages));
    }

    /** @return {@code possible} translated if it looks like a translation key, otherwise its own toString */
    public static String tryTranslate(Object possible) {
        if (possible == null)
            return "";
        String message = possible.toString();
        return TRANSLATION_MATCHER.matcher(message).find() ? tr(message) : message;
    }

    /** Bukkit's {@code ChatColor.translateAlternateColorCodes}, which the port no longer has access to. */
    private static String translateAlternateColorCodes(char altColorChar, String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == altColorChar && ALTERNATE_CODES.indexOf(chars[i + 1]) > -1) {
                chars[i] = ChatFormatting.PREFIX_CODE;
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
    }

    public static void warn(Object... string) {
        LOGGER.warn("{}", SPACE.join(string));
    }

    private static final String ALTERNATE_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";
    private static final Pattern CHAT_NEWLINE = Pattern.compile("<br>|\\n", Pattern.MULTILINE);
    private static final Splitter CHAT_NEWLINE_SPLITTER = Splitter.on(CHAT_NEWLINE);
    private static final Map<String, String> COLORCODE_CONVERTER = new HashMap<>();
    private static final Pattern COLOUR_TAG_MATCHER;
    private static boolean DEBUG = false;
    private static Logger DEBUG_LOGGER;
    private static String ERROR_COLOUR = "<red>";
    private static final Pattern ERROR_MATCHER = Pattern.compile("{{", Pattern.LITERAL);
    private static final Pattern HEX_CODE_MATCHER = Pattern
            .compile("&(#[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f])", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_MATCHER = Pattern
            .compile("&x&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])".replace('&',
                    ChatFormatting.PREFIX_CODE), Pattern.CASE_INSENSITIVE);
    private static String HIGHLIGHT_COLOUR = "<yellow>";
    private static final Pattern HIGHLIGHT_MATCHER = Pattern.compile("[[", Pattern.LITERAL);
    private static final Pattern LEGACY_COLORCODE_MATCHER = Pattern
            .compile(ChatFormatting.PREFIX_CODE + "([0-9a-r])|<([0-9a-f])>", Pattern.CASE_INSENSITIVE);
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("Citizens");
    private static String MESSAGE_COLOUR = "<green>";
    private static boolean RESET_FORMATTING_ON_COLOR_CHANGE = true;
    private static final Joiner SPACE = Joiner.on(" ").useForNull("null");
    private static final Pattern TRANSLATION_MATCHER = Pattern.compile("^[a-zA-Z0-9]+\\.[a-zA-Z0-9]+\\.[a-zA-Z0-9.]+");
    static {
        COLORCODE_CONVERTER.put("0", "<black>");
        COLORCODE_CONVERTER.put("1", "<dark_blue>");
        COLORCODE_CONVERTER.put("2", "<dark_green>");
        COLORCODE_CONVERTER.put("3", "<dark_aqua>");
        COLORCODE_CONVERTER.put("4", "<dark_red>");
        COLORCODE_CONVERTER.put("5", "<dark_purple>");
        COLORCODE_CONVERTER.put("6", "<gold>");
        COLORCODE_CONVERTER.put("7", "<gray>");
        COLORCODE_CONVERTER.put("8", "<dark_gray>");
        COLORCODE_CONVERTER.put("9", "<blue>");
        COLORCODE_CONVERTER.put("a", "<green>");
        COLORCODE_CONVERTER.put("b", "<aqua>");
        COLORCODE_CONVERTER.put("c", "<red>");
        COLORCODE_CONVERTER.put("d", "<light_purple>");
        COLORCODE_CONVERTER.put("e", "<yellow>");
        COLORCODE_CONVERTER.put("f", "<white>");
        COLORCODE_CONVERTER.put("m", "<st>");
        COLORCODE_CONVERTER.put("n", "<u>");
        COLORCODE_CONVERTER.put("k", "<obf>");
        COLORCODE_CONVERTER.put("o", "<i>");
        COLORCODE_CONVERTER.put("l", "<b>");
        COLORCODE_CONVERTER.put("r", "<reset>");

        StringBuilder colourAlternation = new StringBuilder();
        for (ChatFormatting formatting : ChatFormatting.values()) {
            if (formatting.isColor()) {
                colourAlternation.append('<').append(formatting.getName().toLowerCase(Locale.ROOT)).append(">|");
            }
        }
        colourAlternation.append("<#[a-f\\d]{6}>");
        COLOUR_TAG_MATCHER = Pattern.compile(colourAlternation.toString(), Pattern.CASE_INSENSITIVE);
    }
}
