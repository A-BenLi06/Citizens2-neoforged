package net.yuuniverse.interactions;

import java.util.function.Predicate;

/** Resolves Bukkit's vanilla command prefix without rewriting arguments or mod namespaces. */
final class LegacyCommand {
    private LegacyCommand() {
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
