package net.citizensnpcs.api.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

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

    /** A chat input page, also used when an existing value cannot fit the native rename field without data loss. */
    @Menu(type = InventoryType.ANVIL)
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
            return getPrompt(initialValue());
        }

        String initialValue() {
            return initialValue == null ? null : initialValue.get();
        }

        String getPrompt(String current) {
            String heading = title == null || title.isEmpty() ? "Enter a value" : title;
            return current == null ? heading : heading + " [[(currently " + current + ")]]";
        }

        @Override
        public void initialise(MenuContext ctx) {
            if (title != null && !title.isEmpty()) {
                ctx.setTitle(title);
            }
        }
    }

    @Menu(type = InventoryType.ANVIL)
    static final class StringInputPage extends ChatStringInputPage {
        private StringInputPage(String title, Supplier<String> initialValue, Function<String, Boolean> callback) {
            super(title, initialValue, callback);
        }
    }

    /**
     * Opens a native rename field and stays open until the callback accepts. Empty input is null; literal words such as
     * "null" remain text. Existing values outside the native field's length/character limits use chat to avoid truncation.
     */
    public static InventoryMenuPage filteredStringSetter(String title, Supplier<String> initialValue,
            Function<String, Boolean> callback) {
        return new StringInputPage(title, initialValue, callback);
    }

    public static InventoryMenuPage filteredStringSetter(Supplier<String> initialValue,
            Function<String, Boolean> callback) {
        return filteredStringSetter("", initialValue, callback);
    }

    /** Asks for text and always accepts it. */
    public static InventoryMenuPage stringSetter(String title, Supplier<String> initialValue,
            Consumer<String> callback) {
        return new StringInputPage(title, initialValue, input -> {
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

    /** A page-owned prompt whose lifecycle and literal input semantics are supplied by the menu. */
    static Runnable prompt(ServerPlayer player, String description, BooleanSupplier active, Consumer<String> callback) {
        Messaging.send(player.createCommandSourceStack(), description);
        ChatPrompt prompt = new ChatPrompt(null, player, callback, false, active);
        NeoForge.EVENT_BUS.register(prompt);
        return prompt::cancel;
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
        private final BooleanSupplier active;
        private final AtomicBoolean answered = new AtomicBoolean();
        private volatile boolean cancelled;

        private ChatPrompt(InventoryMenu menu, ServerPlayer player, Consumer<String> callback, boolean reopen) {
            this(menu, player, callback, reopen, () -> true);
        }

        private ChatPrompt(InventoryMenu menu, ServerPlayer player, Consumer<String> callback, boolean reopen,
                BooleanSupplier active) {
            this.menu = menu;
            this.player = player;
            this.callback = callback;
            this.reopen = reopen;
            this.active = active;
        }

        @SubscribeEvent
        public void onChat(ServerChatEvent event) {
            if (event.getPlayer() != player || cancelled || !answered.compareAndSet(false, true))
                return;
            event.setCanceled(true);
            String text = event.getRawText();
            // chat arrives off the server thread, so the menu must not be touched until we are back on it
            CitizensAPI.getScheduler().runTask(() -> {
                try {
                    if (cancelled || !active.getAsBoolean())
                        return;
                    callback.accept(menu == null ? text
                            : text.equals("\"\"") || text.equals("''") || text.equals("null") ? "" : text);
                    if (reopen) {
                        menu.present(player);
                    }
                } finally {
                    cancel();
                }
            });
        }

        @SubscribeEvent
        public void onOpen(PlayerContainerEvent.Open event) {
            if (menu == null && event.getEntity() == player) {
                cancel();
            }
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() == player) {
                cancel();
            }
        }

        private void cancel() {
            cancelled = true;
            NeoForge.EVENT_BUS.unregister(this);
        }
    }
}
