package net.citizensnpcs.trait;

import java.util.Locale;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** Native item syntax with the older Citizens material:color / material:components forms. */
final class HologramItem {
    private HologramItem() { }

    record Definition(ItemStack stack, ChatFormatting color) { }

    static boolean containsItem(String text) {
        return text != null && text.contains("<item:");
    }

    static Definition parse(String text, HolderLookup.Provider registries) {
        String spec = specification(text);
        if (spec == null) return null;
        // Resolve a complete native identity first: a mod namespace must not be mistaken for a legacy modifier.
        ItemStack nativeItem = item(spec, registries);
        if (nativeItem != null) return new Definition(nativeItem, null);
        for (int colon = spec.indexOf(':'); colon >= 0; colon = spec.indexOf(':', colon + 1)) {
            String material = spec.substring(0, colon);
            ItemStack base = item(material, registries);
            if (base == null) continue;
            String modifier = spec.substring(colon + 1);
            ChatFormatting color = ChatFormatting.getByName(modifier);
            if (color != null && (color.isColor() || color == ChatFormatting.RESET))
                return new Definition(base, color == ChatFormatting.RESET ? null : color);
            if (modifier.contains("=")) {
                ItemStack modified = item(material + "[" + modifier + "]", registries);
                if (modified != null) return new Definition(modified, null);
            }
        }
        return null;
    }

    private static ItemStack item(String spec, HolderLookup.Provider registries) {
        int bracket = spec.indexOf('[');
        String identity = bracket < 0 ? spec : spec.substring(0, bracket);
        String normalized = identity.trim().toLowerCase(Locale.ROOT).replace(' ', '_')
                + (bracket < 0 ? "" : spec.substring(bracket));
        try {
            StringReader reader = new StringReader(normalized);
            ItemParser.ItemResult parsed = new ItemParser(registries).parse(reader);
            if (reader.canRead()) return null;
            ItemStack stack = new ItemStack(parsed.item(), 1);
            stack.applyComponents(parsed.components());
            ItemStack.validateComponents(stack.getComponents()).getOrThrow();
            return stack.isEmpty() ? null : stack;
        } catch (CommandSyntaxException | RuntimeException ex) {
            return null;
        }
    }

    /** A '>' inside a quoted component string is data, not the end of the markup. */
    private static String specification(String text) {
        if (!containsItem(text)) return null;
        int start = text.indexOf("<item:") + "<item:".length();
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '>') return text.substring(start, i);
        }
        return null;
    }
}
