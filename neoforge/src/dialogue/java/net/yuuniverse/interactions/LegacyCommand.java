package net.yuuniverse.interactions;

import java.util.function.Predicate;
import java.util.Optional;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/** Resolves Bukkit's vanilla command prefix without rewriting arguments or mod namespaces. */
final class LegacyCommand {
    private LegacyCommand() {
    }

    /** Reads one legacy argument, including unquoted Unicode IDs or a quoted name containing spaces.
     * More than one argument belongs to the provider's normal command grammar. */
    static Optional<String> singleArgument(String line) {
        int start = 0;
        while (start < line.length() && !Character.isWhitespace(line.charAt(start))) start++;
        StringReader reader = new StringReader(line.substring(start).strip());
        if (!reader.canRead()) return Optional.empty();
        String value;
        if (StringReader.isQuotedStringStart(reader.peek())) {
            try {
                value = reader.readQuotedString();
            } catch (CommandSyntaxException failure) {
                throw new IllegalArgumentException("Invalid quoted command argument", failure);
            }
        } else {
            int end = 0;
            while (end < reader.getString().length() && !Character.isWhitespace(reader.getString().charAt(end))) end++;
            value = reader.getString().substring(0, end);
            reader.setCursor(end);
        }
        reader.skipWhitespace();
        return reader.canRead() ? Optional.empty() : Optional.of(value);
    }

    static String normalize(String raw, Predicate<String> registered) {
        String line = raw.trim();
        if (line.startsWith("/"))
            line = line.substring(1);
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end)))
            end++;
        String root = line.substring(0, end);
        if (root.startsWith("minecraft:") && !registered.test(root)) {
            String nativeRoot = root.substring("minecraft:".length());
            if (registered.test(nativeRoot))
                return nativeRoot + line.substring(end);
        }
        return line;
    }
}
