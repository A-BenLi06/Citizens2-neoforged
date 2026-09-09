package net.yuuniverse.interactions;

import java.util.Locale;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Stands in for the {@code checkitem} PlaceholderAPI expansion the dialogues are built on.
 * <p>
 * This is not a nicety: {@code %checkitem_lorecontains:<lore>,amt:N%} appears 27099 times across the migrated
 * conversations and {@code %checkitem_remove_lorecontains:…%} another 3311. It <em>is</em> the currency check and the
 * payment step of every shop-like dialogue, so without it those conversations would talk and never transact.
 * <p>
 * The forms that appear in the real data, all of which this understands:
 *
 * <pre>
 * %checkitem_lorecontains:10 UDT 悠日,amt:1%          -&gt; "yes" / "no"
 * %checkitem_remove_lorecontains:[花昙已检],amt:1%    -&gt; removes them, "yes" / "no"
 * %checkitem_namecontains:…,amt:N%
 * %checkitem_matcontains:…,amt:N%
 * %checkitem_mat:minecraft:paper,amt:N%
 * </pre>
 *
 * The comparison is a substring match on the item's <em>rendered</em> text, because that is what the plugin compared and
 * what the dialogue authors wrote against - the stored lore is JSON, and matching the JSON would fail on any styling.
 */
public final class CheckItem {
    private CheckItem() {
    }

    /** A private inventory projection catches overlapping costs before any real items are removed. */
    static final class PaymentPlan {
        private final java.util.List<ItemStack> remaining;

        PaymentPlan(java.util.List<ItemStack> inventory) {
            remaining = inventory.stream().map(ItemStack::copy).toList();
        }

        void reserve(String expansion) {
            Query query = Query.parse(expansion);
            if (query == null)
                throw new IllegalArgumentException("Invalid item payment: " + expansion);
            long available = remaining.stream().filter(stack -> matches(stack, query))
                    .mapToLong(ItemStack::getCount).sum();
            if (available < query.amount)
                throw new IllegalStateException("Insufficient items for combined dialogue payments");
            int left = query.amount;
            for (ItemStack stack : remaining) {
                if (matches(stack, query)) {
                    int take = Math.min(left, stack.getCount());
                    stack.shrink(take);
                    left -= take;
                }
                if (left == 0) break;
            }
        }
    }

    static boolean consume(String expansion, ServerPlayer player) {
        Query query = Query.parse(expansion);
        if (query == null || player == null || count(player, query) < query.amount)
            return false;
        remove(player, query, query.amount);
        return true;
    }

    /** @return the expansion's answer: {@code yes} when enough matching items are held, {@code no} otherwise */
    public static String evaluate(String expansion, ServerPlayer player) {
        Query query = Query.parse(expansion);
        if (query == null || player == null)
            return "no";
        int found = count(player, query);
        if (found < query.amount)
            return "no";
        if (query.remove) {
            remove(player, query, query.amount);
        }
        return "yes";
    }

    /** @return true when the player holds at least {@code amount} matching items (does not remove anything) */
    public static boolean holds(String expansion, ServerPlayer player) {
        Query query = Query.parse(expansion);
        return query != null && player != null && count(player, query) >= query.amount;
    }

    private static int count(ServerPlayer player, Query query) {
        int total = 0;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack, query)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void remove(ServerPlayer player, Query query, int amount) {
        int left = amount;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!matches(stack, query)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
        player.containerMenu.broadcastChanges();
    }

    private static boolean matches(ItemStack stack, Query query) {
        if (stack.isEmpty())
            return false;
        switch (query.kind) {
            case LORE: {
                ItemLore lore = stack.get(DataComponents.LORE);
                if (lore == null)
                    return false;
                for (Component line : lore.lines()) {
                    if (Text.plain(line).contains(query.needle))
                        return true;
                }
                return false;
            }
            case NAME: {
                Component name = stack.get(DataComponents.CUSTOM_NAME);
                return name != null && Text.plain(name).contains(query.needle);
            }
            case MATERIAL_CONTAINS:
                return id(stack).contains(query.needle.toLowerCase(Locale.ROOT));
            case MATERIAL_EXACT:
                return id(stack).equals(normaliseId(query.needle));
            default:
                return false;
        }
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * A Bukkit material name reaches here as {@code PROSPEROUS_UDT_102024} or {@code REFURBISHED_FURNITURE_PACKAGE};
     * the same items on NeoForge are {@code prosperous:udt_102024} and {@code refurbished_furniture:package}.
     * <p>
     * The namespace cannot be found by taking the text before the first underscore, because namespaces contain
     * underscores themselves - that reading turns the second example into {@code refurbished:furniture_package}, which
     * exists nowhere. Every split point is tried instead and the one the registry knows wins; an id that matches nothing
     * anywhere is returned as vanilla so the caller can report it by its real name.
     */
    static String normaliseId(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.indexOf(':') >= 0)
            return value;
        String alias = ItemAliases.lookup(value);
        if (alias != null)
            return alias;
        if (BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath("minecraft", value)))
            return "minecraft:" + value;
        for (int i = value.indexOf('_'); i > 0; i = value.indexOf('_', i + 1)) {
            String namespace = value.substring(0, i);
            String path = value.substring(i + 1);
            if (!ResourceLocation.isValidNamespace(namespace) || !ResourceLocation.isValidPath(path)) {
                continue;
            }
            if (BuiltInRegistries.ITEM.containsKey(ResourceLocation.fromNamespaceAndPath(namespace, path)))
                return namespace + ":" + path;
        }
        return "minecraft:" + value;
    }

    private enum Kind {
        LORE, NAME, MATERIAL_CONTAINS, MATERIAL_EXACT
    }

    /** One parsed {@code checkitem} expansion. */
    static class Query {
        Kind kind;
        String needle;
        int amount = 1;
        boolean remove;

        static Query parse(String raw) {
            if (raw == null)
                return null;
            String body = raw.trim();
            if (body.startsWith("%") && body.endsWith("%")) {
                body = body.substring(1, body.length() - 1);
            }
            if (!body.startsWith("checkitem_"))
                return null;
            body = body.substring("checkitem_".length());
            Query query = new Query();
            if (body.startsWith("remove_")) {
                query.remove = true;
                body = body.substring("remove_".length());
            }
            int colon = body.indexOf(':');
            String kind = colon < 0 ? body : body.substring(0, colon);
            String rest = colon < 0 ? "" : body.substring(colon + 1);
            switch (kind) {
                case "lorecontains":
                    query.kind = Kind.LORE;
                    break;
                case "namecontains":
                    query.kind = Kind.NAME;
                    break;
                case "matcontains":
                    query.kind = Kind.MATERIAL_CONTAINS;
                    break;
                case "mat":
                    query.kind = Kind.MATERIAL_EXACT;
                    break;
                default:
                    return null;
            }
            // the amount rides on the end as ",amt:N" - the needle itself may contain commas, so split from the right
            int amt = rest.lastIndexOf(",amt:");
            if (amt >= 0) {
                try {
                    query.amount = Integer.parseInt(rest.substring(amt + 5).trim());
                    if (query.amount <= 0) return null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
                rest = rest.substring(0, amt);
            }
            query.needle = rest;
            return query;
        }
    }
}
