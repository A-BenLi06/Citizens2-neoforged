package net.citizensnpcs.api.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Ready-made menu pages for the small inputs a configuration GUI keeps needing: a toggle button, a one-of-many picker, a
 * set of independent toggles, and a text field.
 */
public class InputMenus {
    private InputMenus() {
    }

    /** A button that flips a boolean and rewrites its own lore to show the new state. */
    public static class BooleanSlotHandler implements Consumer<CitizensInventoryClickEvent> {
        private final Function<Boolean, String> transformer;
        private boolean value;

        public BooleanSlotHandler(Function<Boolean, String> transformer) {
            this(transformer, false);
        }

        public BooleanSlotHandler(Function<Boolean, String> transformer, boolean initial) {
            this.transformer = transformer;
            this.value = initial;
        }

        @Override
        public void accept(CitizensInventoryClickEvent event) {
            value = !value;
            for (ServerPlayer viewer : event.getViewers()) {
                viewer.level().playSound(null, viewer.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(),
                        SoundSource.MASTER, 1, 0);
            }
            event.setCurrentItemDescription(transformer.apply(value));
            event.setCancelled(true);
        }

        public boolean getValue() {
            return value;
        }
    }

    /** One option in a {@link #picker} or {@link #toggle} menu. */
    public static class Choice<T> {
        private boolean active;
        private String description;
        private String material;
        private T value;

        public ItemStack createDisplayItem() {
            ItemStack item = MenuItems.byId(material, 1);
            if (item.isEmpty())
                return item;
            String name = null;
            List<String> lore = new ArrayList<>();
            String described = getDescription();
            if (described != null && described.contains("\n")) {
                String[] parts = described.split("\n", 2);
                name = parts[0];
                for (String line : parts[1].split("\n")) {
                    lore.add(line);
                }
            } else if (value instanceof Enum<?> constant) {
                // an enum choice labels itself, since the description is then free to be the explanation
                String raw = constant.name();
                name = raw.charAt(0) + raw.substring(1).toLowerCase(Locale.ROOT);
                if (described != null) {
                    for (String line : described.split("\n")) {
                        lore.add(line);
                    }
                }
            }
            MenuItems.setDisplayName(item, (active ? ChatFormatting.GREEN : ChatFormatting.RED) + (name == null ? "" : name));
            if (!lore.isEmpty()) {
                MenuItems.setLore(item, lore);
            }
            MenuItems.hideAttributes(item);
            return item;
        }

        public String getDescription() {
            return description == null ? null : Messaging.parseComponents(description);
        }

        public String getDisplayMaterial() {
            return material;
        }

        public T getValue() {
            return value;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public enum Type {
            PICKER,
            TOGGLE;
        }

        /**
         * @param display
         *            the item to show, as a registry id. Upstream takes a Bukkit {@code Material} here; ids are used
         *            throughout this port because Minecraft items are registry objects.
         */
        public static <T> Choice<T> of(T value, String display, String description, boolean active) {
            Choice<T> ret = new Choice<>();
            ret.active = active;
            ret.material = display;
            ret.value = value;
            ret.description = description;
            return ret;
        }
    }

    /**
     * A grid of choices. A picker closes as soon as one is chosen; a toggle set stays open and reports every active
     * choice when it closes.
     */
    @Menu(type = InventoryType.CHEST)
    private static class ChoiceInputMenu<T> extends InventoryMenuPage {
        private final Consumer<List<Choice<T>>> callback;
        private final Choice<T>[] choices;
        private final String title;
        private final Choice.Type type;

        private ChoiceInputMenu(String title, Choice.Type type, Consumer<List<Choice<T>>> callback,
                Choice<T>[] choices) {
            this.title = title;
            this.callback = callback;
            this.choices = choices;
            this.type = type;
        }

        @Override
        public Container createContainer(String ignored) {
            // choices sit in every other slot, so the container has to be twice as wide as the number of them
            if (choices.length <= 3)
                return new SimpleContainer(5);
            return new SimpleContainer(Math.min(54, choices.length / 5 * 9 + 9));
        }

        @Override
        public void initialise(MenuContext ctx) {
            ctx.setTitle(title);
            for (int i = 0; i < choices.length; i++) {
                Choice<T> choice = choices[i];
                int index = i * 2;
                if (index >= ctx.getSize()) {
                    break;
                }
                InventoryMenuSlot slot = ctx.getSlot(index);
                slot.setItemStack(choice.createDisplayItem());
                slot.setClickHandler(event -> {
                    event.setCancelled(true);
                    boolean newState = !choice.isActive();
                    if (type == Choice.Type.TOGGLE) {
                        choice.setActive(newState);
                        slot.setItemStack(choice.createDisplayItem());
                        return;
                    }
                    for (Choice<T> other : choices) {
                        other.setActive(false);
                    }
                    choice.setActive(true);
                    ctx.getMenu().transitionBack();
                });
            }
        }

        @Override
        public void onClose(ServerPlayer player) {
            List<Choice<T>> active = new ArrayList<>(choices.length);
            for (Choice<T> choice : choices) {
                if (choice.isActive()) {
                    active.add(choice);
                }
            }
            callback.accept(active);
        }
    }

    /**
     * A page that asks for a line of text.
     * <p>
     * Upstream opens an anvil and reads what the player types into its rename field. That needs a menu which really is a
     * vanilla {@code AnvilMenu}, because the rename packet is delivered nowhere else, and is not ported yet — so this page
     * is recognised by {@link InventoryMenu} and turned into a chat question instead of a container. The flow is the same
     * from the caller's side: ask, validate, and only leave the page when the value is accepted.
     */
    public static class ChatStringInputPage extends InventoryMenuPage {
        private final Function<String, Boolean> callback;
        private final Supplier<String> initialValue;
        private final String title;

        private ChatStringInputPage(String title, Supplier<String> initialValue, Function<String, Boolean> callback) {
            this.title = title;
            this.initialValue = initialValue;
            this.callback = callback;
        }

        /** @return true when the value was accepted and the page should close */
        public boolean accept(String input) {
            return callback.apply(input);
        }

        public String getPrompt() {
            String current = initialValue == null ? null : initialValue.get();
            String heading = title == null || title.isEmpty() ? "Enter a value" : title;
            return current == null ? heading : heading + " [[(currently " + current + ")]]";
        }

        @Override
        public void initialise(MenuContext ctx) {
        }
    }

    /** Asks for text, and only closes the page when {@code callback} accepts the value. */
    public static InventoryMenuPage filteredStringSetter(String title, Supplier<String> initialValue,
            Function<String, Boolean> callback) {
        return new ChatStringInputPage(title, initialValue, callback);
    }

    public static InventoryMenuPage filteredStringSetter(Supplier<String> initialValue,
            Function<String, Boolean> callback) {
        return filteredStringSetter("", initialValue, callback);
    }

    /** Asks for text and always accepts it. */
    public static InventoryMenuPage stringSetter(String title, Supplier<String> initialValue,
            Consumer<String> callback) {
        return new ChatStringInputPage(title, initialValue, input -> {
            callback.accept(input);
            return true;
        });
    }

    public static InventoryMenuPage stringSetter(Supplier<String> initialValue, Consumer<String> callback) {
        return stringSetter("", initialValue, callback);
    }

    public static BooleanSlotHandler clickToggle(Function<Boolean, String> transformer, boolean initialValue) {
        return new BooleanSlotHandler(transformer, initialValue);
    }

    /** One-of-many. The callback receives the chosen option, or null if the menu was closed without choosing. */
    @SafeVarargs
    public static <T> InventoryMenuPage picker(String title, Consumer<Choice<T>> callback, Choice<T>... choices) {
        return new ChoiceInputMenu<>(title, Choice.Type.PICKER,
                chosen -> callback.accept(chosen.isEmpty() ? null : chosen.get(0)), choices);
    }

    /** Any-of-many. The callback receives every active option when the menu closes. */
    @SafeVarargs
    public static <T> InventoryMenuPage toggle(String title, Consumer<List<Choice<T>>> callback,
            Choice<T>... choices) {
        return new ChoiceInputMenu<>(title, Choice.Type.TOGGLE, callback, choices);
    }

    public static BooleanSlotHandler toggler(Consumer<Boolean> consumer, boolean initialValue) {
        return new BooleanSlotHandler(value -> {
            consumer.accept(value);
            return value ? ChatFormatting.GREEN + "On" : ChatFormatting.RED + "Off";
        }, initialValue);
    }

    /**
     * Asks for a line of text in chat: closes the menu, prompts, waits for the next thing that player says, then reopens.
     * <p>
     * Upstream also offers an anvil-based variant that types into the rename field. That needs a menu that really is a
     * vanilla {@code AnvilMenu}, because the rename packet is only delivered to one, and is not yet ported — so
     * {@link #stringSetter} routes here too. The chat route is upstream's own fallback, not an invention, and it is fully
     * functional; only the in-GUI look differs.
     */
    public static void runChatStringSetter(InventoryMenu menu, ServerPlayer player, String description,
            Consumer<String> callback) {
        ask(menu, player, description, callback, true);
    }

    /**
     * Asks without reopening the menu afterwards, leaving that to the caller — a text-input page has to decide whether to
     * accept the answer and move on, or ask again.
     */
    static void askOnce(InventoryMenu menu, ServerPlayer player, String description, Consumer<String> callback) {
        ask(menu, player, description, callback, false);
    }

    private static void ask(InventoryMenu menu, ServerPlayer player, String description, Consumer<String> callback,
            boolean reopen) {
        menu.close(player);
        Messaging.send(player.createCommandSourceStack(), description);
        NeoForge.EVENT_BUS.register(new ChatPrompt(menu, player, callback, reopen));
    }

    /** A one-shot chat listener. Registered as an object so it can unregister itself once it has its answer. */
    private static class ChatPrompt {
        private final Consumer<String> callback;
        private final InventoryMenu menu;
        private final ServerPlayer player;
        private final boolean reopen;

        private ChatPrompt(InventoryMenu menu, ServerPlayer player, Consumer<String> callback, boolean reopen) {
            this.menu = menu;
            this.player = player;
            this.callback = callback;
            this.reopen = reopen;
        }

        @SubscribeEvent
        public void onChat(ServerChatEvent event) {
            if (event.getPlayer() != player)
                return;
            NeoForge.EVENT_BUS.unregister(this);
            event.setCanceled(true);
            String text = event.getRawText();
            // chat arrives off the server thread, so the menu must not be touched until we are back on it
            CitizensAPI.getScheduler().runTask(() -> {
                callback.accept(text.equals("\"\"") || text.equals("''") || text.equals("null") ? "" : text);
                if (reopen) {
                    menu.present(player);
                }
            });
        }
    }
}
