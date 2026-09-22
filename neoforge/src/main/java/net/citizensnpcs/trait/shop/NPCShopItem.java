package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Joiner;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.expr.CompiledExpression;
import net.citizensnpcs.api.expr.ExpressionEngine.ExpressionCompileException;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.gui.MenuItems;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.Persistable;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.StoredItems;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.trait.shop.NPCShopAction.Transaction;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * One thing a shop sells: what it looks like, what it costs, what the buyer gets, and how often it may be bought.
 */
public class NPCShopItem implements Cloneable, Persistable {
    @Persist
    String alreadyPurchasedMessage;
    @Persist
    String clickToConfirmMessage;
    @Persist
    private List<NPCShopAction> cost = new ArrayList<>();
    @Persist
    String costMessage;
    private List<String> defaultLore = List.of();
    private String defaultName;
    ItemStack display;
    private StoredItems<String> stored = new StoredItems<>();
    @Persist
    int globalTimesPurchasable;
    @Persist
    String isClickableExpression;
    @Persist
    String isVisibleExpression;
    @Persist
    boolean maxRepeatsOnShiftClick;
    @Persist
    private int npurchases;
    @Persist(keyType = UUID.class)
    final Map<UUID, Integer> purchases = new HashMap<>();
    @Persist
    private List<NPCShopAction> result = new ArrayList<>();
    @Persist
    String resultMessage;
    @Persist
    int timesPurchasable;

    public NPCShopItem() {
        Map<String, Object> defaults = Setting.SHOP_DEFAULT_ITEM_SETTINGS.asMap();
        alreadyPurchasedMessage = defaultString(defaults, "already-purchased-message");
        clickToConfirmMessage = defaultString(defaults, "click-to-confirm-message");
        costMessage = defaultString(defaults, "cost-message");
        resultMessage = defaultString(defaults, "result-message");
        maxRepeatsOnShiftClick = Boolean.parseBoolean(raw(defaults, "max-repeats-on-shift-click"));
        timesPurchasable = defaultInt(defaults, "times-purchasable");
        globalTimesPurchasable = defaultInt(defaults, "global-times-purchasable");
        String lore = raw(defaults, "lore");
        if (!lore.isEmpty()) {
            defaultLore = Messaging.parseComponentsList(lore);
        }
        // upstream reads the default name from getString("") rather than getString("name"), so a configured default name
        // never applies
        String name = raw(defaults, "name");
        if (!name.isEmpty()) {
            defaultName = Messaging.parseComponents(name);
        }
    }

    private static String raw(Map<String, Object> defaults, String key) {
        Object value = defaults.get(key);
        return value == null ? "" : value.toString();
    }

    private static String defaultString(Map<String, Object> defaults, String key) {
        String value = raw(defaults, key);
        return value.isEmpty() ? null : Messaging.parseComponents(value);
    }

    private static int defaultInt(Map<String, Object> defaults, String key) {
        try {
            String value = raw(defaults, key);
            return value.isEmpty() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Runs each action in turn, undoing the ones that succeeded if any of them turns out to be impossible.
     *
     * @return the transactions that ran, or null if the whole set could not be applied
     */
    private List<Transaction> apply(List<NPCShopAction> actions, Function<NPCShopAction, Transaction> func) {
        List<Transaction> pending = new ArrayList<>();
        try {
            for (NPCShopAction action : actions) {
                Transaction take = func.apply(action);
                if (!take.isPossible()) {
                    rollback(pending);
                    return null;
                }
                take.run();
                pending.add(take);
            }
        } catch (RuntimeException failure) {
            rollback(pending);
            org.slf4j.LoggerFactory.getLogger(NPCShopItem.class).error("NPC shop transaction failed", failure);
            return null;
        }
        return pending;
    }

    private static void rollback(List<Transaction> completed) {
        try { Transaction.rollbackAll(completed); }
        catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(NPCShopItem.class).error("NPC shop refund failed; manual reconciliation required", failure);
        }
    }

    private void changeAction(List<NPCShopAction> source, Function<NPCShopAction, Boolean> filter,
            NPCShopAction delta) {
        for (int i = 0; i < source.size(); i++) {
            if (filter.apply(source.get(i))) {
                if (delta == null) {
                    source.remove(i);
                } else {
                    source.set(i, delta);
                }
                return;
            }
        }
        if (delta != null) {
            source.add(delta);
        }
    }

    void changeCost(Function<NPCShopAction, Boolean> filter, NPCShopAction cost) {
        changeAction(this.cost, filter, cost);
    }

    void changeResult(Function<NPCShopAction, Boolean> filter, NPCShopAction result) {
        changeAction(this.result, filter, result);
    }

    @Override
    public NPCShopItem clone() {
        try {
            NPCShopItem dup = (NPCShopItem) super.clone();
            dup.stored = stored.copy();
            dup.display = display == null ? null : display.copy();
            dup.cost = new ArrayList<>();
            for (NPCShopAction src : cost) {
                dup.cost.add(src.clone());
            }
            dup.result = new ArrayList<>();
            for (NPCShopAction src : result) {
                dup.result.add(src.clone());
            }
            return dup;
        } catch (CloneNotSupportedException e) {
            throw new Error(e);
        }
    }

    public List<NPCShopAction> getCost() {
        return cost;
    }

    public List<NPCShopAction> getResult() {
        return result;
    }

    /**
     * The stack to show a particular player, with placeholders filled in.
     *
     * @return null when a visibility expression says this player should not see the item at all
     */
    public ItemStack getDisplayItem(ServerPlayer player) {
        if (display == null || display.isEmpty())
            return null;
        if (isVisibleExpression != null && !evaluate(isVisibleExpression, player))
            return null;
        ItemStack stack = display.copy();
        String name = MenuItems.getDisplayName(stack);
        if (name != null) {
            MenuItems.setDisplayName(stack, placeholders(name, player));
        }
        List<String> lore = MenuItems.getLore(stack);
        if (Setting.SHOP_USE_DEFAULT_DESCRIPTION.asBoolean() && lore.isEmpty()) {
            List<String> generated = new ArrayList<>();
            cost.forEach(c -> generated.add(c.describe()));
            result.forEach(r -> {
                // a command has nothing useful to say to a buyer, and would give away the shop's internals
                if (!(r instanceof CommandAction)) {
                    generated.add(r.describe());
                }
            });
            if (timesPurchasable > 0) {
                generated.add("Times purchasable: " + timesPurchasable);
            }
            lore = generated;
        }
        if (!lore.isEmpty()) {
            List<String> replaced = new ArrayList<>(lore.size());
            for (String line : lore) {
                replaced.add(placeholders(line, player));
            }
            MenuItems.setLore(stack, replaced);
        }
        return stack;
    }

    /** Compiles and evaluates one of the item's expressions, defaulting to visible/clickable if it will not compile. */
    private boolean evaluate(String expression, ServerPlayer player) {
        try {
            CompiledExpression compiled = CitizensAPI.getExpressionRegistry().compile(expression);
            return compiled.evaluateAsBoolean(ExpressionScope.create(player));
        } catch (ExpressionCompileException e) {
            Messaging.severe("Could not compile shop item expression", expression, "-", e.getMessage());
            return true;
        }
    }

    void onClick(NPCShop shop, NPCShopStorage storage, ServerPlayer player, InventoryMultiplexer inventory,
            boolean shiftClick, boolean secondClick) {
        if (isClickableExpression != null && !evaluate(isClickableExpression, player))
            return;
        if (globalTimesPurchasable > 0 && npurchases >= globalTimesPurchasable)
            return;
        if (timesPurchasable > 0 && purchases.getOrDefault(player.getUUID(), 0) >= timesPurchasable) {
            if (alreadyPurchasedMessage != null) {
                Messaging.sendColorless(player.createCommandSourceStack(),
                        placeholders(alreadyPurchasedMessage, player));
            }
            return;
        }
        if (clickToConfirmMessage != null && !secondClick) {
            Messaging.sendColorless(player.createCommandSourceStack(), placeholders(clickToConfirmMessage, player));
            return;
        }
        int max = Integer.MAX_VALUE;
        if (maxRepeatsOnShiftClick && shiftClick) {
            for (NPCShopAction action : cost) {
                int repeats = action.getMaxRepeats(player, inventory);
                if (repeats != -1) {
                    max = Math.min(max, repeats);
                }
            }
            if (max == 0)
                return;
        }
        int repeats = max == Integer.MAX_VALUE ? 1 : max;
        List<Transaction> take = apply(cost, action -> action.take(storage, player, inventory, repeats));
        if (take == null) {
            if (costMessage != null) {
                Messaging.sendColorless(player.createCommandSourceStack(), placeholders(costMessage, player));
            }
            return;
        }
        if (apply(result, action -> action.grant(storage, player, inventory, repeats)) == null) {
            rollback(take);
            return;
        }
        if (resultMessage != null) {
            Messaging.sendColorless(player.createCommandSourceStack(), placeholders(resultMessage, player));
        }
        if (timesPurchasable > 0) {
            purchases.put(player.getUUID(), purchases.getOrDefault(player.getUUID(), 0) + 1);
        }
        if (globalTimesPurchasable > 0) {
            npurchases++;
        }
        new NPCShopPurchaseEvent(player, shop, this).callEvent();
    }

    /** Fills in {@code <cost>}, {@code <result>} and {@code <times_purchasable>} as well as the usual placeholders. */
    private String placeholders(String string, ServerPlayer player) {
        string = Placeholders.replace(string, player);
        StringBuilder sb = new StringBuilder();
        Matcher matcher = PLACEHOLDER_REGEX.matcher(string);
        int last = 0;
        while (matcher.find()) {
            sb.append(string, last, matcher.start());
            last = matcher.end();
            if (matcher.group(1).equalsIgnoreCase("times_purchasable")) {
                sb.append(timesPurchasable);
                continue;
            }
            List<NPCShopAction> actions = matcher.group(1).equalsIgnoreCase("cost") ? cost : result;
            if (!actions.isEmpty()) {
                List<String> described = new ArrayList<>(actions.size());
                for (NPCShopAction action : actions) {
                    described.add(action.describe());
                }
                // appended literally rather than through appendReplacement, so a description containing $ or { needs no
                // escaping and cannot be mistaken for a group reference
                sb.append(Joiner.on(", ").join(described));
            }
        }
        sb.append(string, last, string.length());
        return sb.toString();
    }

    public void resetPurchaseHistory() {
        purchases.clear();
    }

    public void resetPurchaseHistory(UUID playerUUID) {
        purchases.remove(playerUUID);
    }

    public void setDisplayItem(ItemStack itemstack) {
        stored.clear();
        display = itemstack == null || itemstack.isEmpty() ? null : itemstack.copy();
        if (display == null)
            return;
        if (defaultName != null) {
            MenuItems.setDisplayName(display,
                    defaultName.replace("<itemname>", display.getHoverName().getString()));
        }
        if (!defaultLore.isEmpty()) {
            List<String> existing = MenuItems.getLore(display);
            List<String> output = new ArrayList<>();
            for (String lore : defaultLore) {
                if (lore.trim().equals("<itemlore>")) {
                    output.addAll(existing);
                } else {
                    output.add(lore);
                }
            }
            MenuItems.setLore(display, output);
        }
    }

    private static final Pattern PLACEHOLDER_REGEX = Pattern.compile("<(cost|result|times_purchasable)>",
            Pattern.CASE_INSENSITIVE);

    public boolean hasUnresolvedDisplay() { return stored.contains("display"); }
    @Override public void load(DataKey root) { display = stored.load("display", root.getRelative("display")); }
    @Override public void save(DataKey root) { stored.save("display", root.getRelative("display"), display); }
}
