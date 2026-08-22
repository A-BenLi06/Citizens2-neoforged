package net.citizensnpcs.api.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.citizensnpcs.api.util.Messaging;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;

/**
 * Building the display items menus are made of.
 * <p>
 * Upstream reaches for {@code ItemMeta} — {@code setDisplayName}, {@code setLore}, {@code addItemFlags} — which does not
 * exist here; the equivalents are the {@code CUSTOM_NAME}, {@code LORE} and {@code ATTRIBUTE_MODIFIERS} data components.
 * Kept in one place because the slot, the click event and {@link InputMenus} all need the same three operations.
 */
public class MenuItems {
    private MenuItems() {
    }

    /**
     * @param id
     *            a registry id, with or without a namespace
     * @return a stack of that item, or an empty stack when the id names nothing
     */
    public static ItemStack byId(String id, int amount) {
        if (id == null || id.isEmpty())
            return ItemStack.EMPTY;
        ResourceLocation key = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        if (key == null)
            return ItemStack.EMPTY;
        Item item = BuiltInRegistries.ITEM.get(key);
        if (item == Items.AIR)
            return ItemStack.EMPTY;
        return new ItemStack(item, Math.max(1, amount));
    }

    /**
     * @return the display name as a plain string, or null when the stack has none. Used to read back what a player typed
     *         into an anvil menu.
     */
    public static String getDisplayName(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return null;
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name == null ? null : name.getString();
    }

    /**
     * @return the lore lines as plain strings, empty when the stack has none
     */
    public static List<String> getLore(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return new ArrayList<>();
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null)
            return new ArrayList<>();
        List<String> lines = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) {
            lines.add(line.getString());
        }
        return lines;
    }

    /**
     * Stops the tooltip listing attribute modifiers, which is what upstream's {@code HIDE_ATTRIBUTES} flag is for — a
     * menu button made out of a sword should not advertise its damage.
     */
    public static void hideAttributes(ItemStack stack) {
        if (stack == null || stack.isEmpty())
            return;
        stack.set(DataComponents.ATTRIBUTE_MODIFIERS,
                stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).withTooltip(false));
    }

    public static void setDisplayName(ItemStack stack, String name) {
        if (stack == null || stack.isEmpty() || name == null)
            return;
        stack.set(DataComponents.CUSTOM_NAME, Messaging.minecraftComponentFromRawMessage(name));
    }

    /** Splits on a literal newline, an escaped one, or {@code <br>}, as upstream does. */
    public static void setLore(ItemStack stack, String joined) {
        if (stack == null || stack.isEmpty())
            return;
        stack.set(DataComponents.LORE, new ItemLore(parseLore(joined)));
    }

    public static void setLore(ItemStack stack, List<String> lines) {
        if (stack == null || stack.isEmpty())
            return;
        List<Component> parsed = new ArrayList<>(lines.size());
        for (String line : lines) {
            parsed.add(Messaging.minecraftComponentFromRawMessage(line));
        }
        stack.set(DataComponents.LORE, new ItemLore(parsed));
    }

    private static List<Component> parseLore(String joined) {
        List<Component> lines = new ArrayList<>();
        if (joined == null || joined.isEmpty())
            return lines;
        for (String line : NEWLINE.split(joined)) {
            lines.add(Messaging.minecraftComponentFromRawMessage(line));
        }
        return lines;
    }

    private static final Pattern NEWLINE = Pattern.compile("\r\n|\n|\\\\n|<br>");
}
