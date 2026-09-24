package net.citizensnpcs.api.gui;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * A stack of {@link InventoryMenuPage}s presented to one or more players as a container GUI.
 * <p>
 * Each page declares its slots, transitions and patterns with annotations; concrete
 * {@link InventoryMenuSlot}/{@link InventoryMenuTransition}/{@link InventoryMenuPattern} fields are injected at runtime,
 * as are {@link InjectContext} fields sourced from the {@link MenuContext} data map. Clicking a
 * {@link MenuTransition} slot pushes the current page and opens the next; closing a page pops back to the one beneath.
 * <p>
 * Differences from upstream, all forced by the platform rather than chosen:
 * <ul>
 * <li>Upstream is a Bukkit {@code Listener} and reacts to {@code InventoryClickEvent}. There is no such event here, so
 * {@link CitizensMenuContainer} calls in directly and the menu needs no registration — {@code createSelfRegistered} is
 * therefore the same as {@code create}, and is kept only so call sites port unchanged.</li>
 * <li>Bukkit lets a plugin swap the inventory a player is looking at. Minecraft ties a container to the screen the client
 * opened, so a transition closes and reopens; the shared {@link Container} carries the items across.</li>
 * <li>Shift-click callbacks describe each actual menu-side pickup or placement while the native container owns slot
 * order, stack limits and lifecycle checks.</li>
 * </ul>
 */
public class InventoryMenu implements Runnable {
    private final List<Runnable> closeCallbacks = new ArrayList<>();
    private boolean closing;
    private PageContext page;
    private final Deque<PageContext> stack = new ArrayDeque<>();
    private boolean transitioning;
    private final Map<ServerPlayer, AbstractContainerMenu> viewers = new LinkedHashMap<>();
    private final Map<ServerPlayer, Runnable> chatPrompts = new HashMap<>();
    private final Map<ServerPlayer, Object> pendingReturns = new HashMap<>();

    private InventoryMenu(InventoryMenuInfo info, InventoryMenuPage instance) {
        transition(info, instance, new HashMap<>());
    }

    private InventoryMenu(InventoryMenuInfo info, Map<String, Object> context) {
        transition(info, info.createInstance(), context);
    }

    /** Closes the menu for every viewer. */
    public void close() {
        pendingReturns.clear();
        cancelChatPrompts();
        closing = true;
        for (ServerPlayer player : new ArrayList<>(viewers.keySet())) {
            if (page != null) {
                page.page.onClose(player);
            }
            if (player.containerMenu == viewers.get(player)) {
                player.closeContainer();
            }
        }
        viewers.clear();
        closing = false;
        for (Runnable callback : closeCallbacks) {
            callback.run();
        }
    }

    /** Closes the menu for one viewer, leaving it open for any others. */
    public void close(ServerPlayer player) {
        pendingReturns.remove(player);
        cancelChatPrompt(player);
        if (!viewers.containsKey(player))
            return;
        closing = true;
        AbstractContainerMenu container = viewers.remove(player);
        if (player.containerMenu == container) {
            player.closeContainer();
        }
        closing = false;
    }

    public Collection<ServerPlayer> getViewers() {
        return viewers.keySet();
    }

    /** The current page and player session still own this exact container. */
    boolean isCurrent(AbstractContainerMenu container, Player player) {
        return page != null && !closing && !transitioning && player instanceof ServerPlayer viewer
                && !viewer.hasDisconnected() && viewer.containerMenu == container && viewers.get(viewer) == container
                && viewer.getServer().getPlayerList().getPlayer(viewer.getUUID()) == viewer;
    }

    /** Shows the menu to a player. Several players may share one menu; transitions affect all of them. */
    public void present(ServerPlayer player) {
        if (page == null)
            return;
        openFor(player);
        pendingReturns.remove(player);
    }

    @Override
    public void run() {
        if (page == null || transitioning)
            return;
        page.page.run();
    }

    public void addCloseCallback(Runnable run) {
        closeCallbacks.add(run);
    }

    /**
     * Runs a click through the current page: the slot's own handlers first, then the page, then any transition sitting on
     * that slot.
     */
    void handleClick(CitizensInventoryClickEvent event) {
        if (page == null || transitioning || closing) {
            event.setCancelled(true);
            return;
        }
        try {
            InventoryMenuSlot slot = page.ctx.getSlot(event.getSlot());
            PageContext before = page;
            Class<? extends InventoryMenuPage> destination = destinationOf(slot);
            slot.onClick(event);
            before.page.onClick(slot, event);
            if (before != page) {
                // a handler transitioned us; the click must not also be applied to the old page
                event.setCancelled(true);
                return;
            }
            if (destination != null) {
                // A transition slot normally has no click handler, and a slot with no handler is locked by default -
                // which in upstream cancels the click before the transition is ever looked at, so @MenuTransition never
                // fires there. Nothing upstream uses the annotation, so the defect is invisible; it is public API here
                // and is honoured. The click stays cancelled either way, because the item must not move.
                event.setCancelled(true);
                transition(destination);
                return;
            }
            if (event.isCancelled())
                return;
        } catch (Exception ex) {
            Messaging.severe("Error handling menu click");
            ex.printStackTrace();
            event.setCancelled(true);
            close();
        }
    }

    /** Called by {@link CitizensMenuContainer} when a viewer's screen closes. */
    void onContainerClosed(AbstractContainerMenu container, Player player) {
        if (closing || transitioning || page == null)
            return;
        if (!(player instanceof ServerPlayer serverPlayer) || viewers.get(serverPlayer) != container)
            return;
        viewers.remove(serverPlayer);
        page.page.onClose(serverPlayer);
        // closing the screen means "go back a page", and popping the last page ends the menu
        transitionBack(serverPlayer);
        if (page != null) {
            // ServerPlayer.doCloseContainer resets containerMenu after removed() returns. Reopen on the next tick,
            // only if no later transition, explicit close, replacement screen or login has superseded this return.
            PageContext target = page;
            Object token = new Object();
            pendingReturns.put(serverPlayer, token);
            CitizensAPI.getScheduler().runTask(() -> {
                if (!pendingReturns.remove(serverPlayer, token) || page != target
                        || serverPlayer.hasDisconnected() || serverPlayer.containerMenu != serverPlayer.inventoryMenu
                        || serverPlayer.getServer().getPlayerList().getPlayer(serverPlayer.getUUID()) != serverPlayer)
                    return;
                present(serverPlayer);
            });
        }
    }

    /** Resends the open-screen packet so the client redraws the title without losing the container. */
    void updateTitle(String newTitle) {
        for (Map.Entry<ServerPlayer, AbstractContainerMenu> entry : viewers.entrySet()) {
            AbstractContainerMenu container = entry.getValue();
            if (container == entry.getKey().inventoryMenu)
                continue;
            entry.getKey().connection.send(new ClientboundOpenScreenPacket(container.containerId,
                    (MenuType<?>) container.getType(), Messaging.minecraftComponentFromRawMessage(newTitle)));
            container.sendAllDataToRemote();
        }
    }

    /** Transition to another page, pushing the current one so that closing returns to it. */
    public void transition(Class<? extends InventoryMenuPage> clazz) {
        transition(clazz, new HashMap<>());
    }

    public void transition(Class<? extends InventoryMenuPage> clazz, Map<String, Object> context) {
        InventoryMenuInfo info = infoFor(clazz);
        transition(info, info.createInstance(), context);
    }

    public void transition(InventoryMenuPage instance) {
        transition(instance, new HashMap<>());
    }

    public void transition(InventoryMenuPage instance, Map<String, Object> context) {
        transition(infoFor(instance.getClass()), instance, context);
    }

    /** Pops back to the page beneath, or ends the menu when this was the last one. */
    public void transitionBack() {
        transitionBack(null);
    }

    private void transitionBack(ServerPlayer closedBy) {
        if (page == null)
            return;
        pendingReturns.clear();
        cancelChatPrompts();
        for (ServerPlayer player : new ArrayList<>(viewers.keySet())) {
            page.page.onClose(player);
        }
        Map<String, Object> data = page.ctx.data();
        page = stack.pollLast();
        if (page != null) {
            page.ctx.data().putAll(data);
            page.page.initialise(page.ctx);
        }
        data.clear();
        if (page == null) {
            closing = true;
            for (ServerPlayer player : new ArrayList<>(viewers.keySet())) {
                if (player != closedBy && player.containerMenu == viewers.get(player)) {
                    player.closeContainer();
                }
            }
            viewers.clear();
            closing = false;
            for (Runnable callback : closeCallbacks) {
                callback.run();
            }
            return;
        }
        reopenAll();
    }

    private void transition(InventoryMenuInfo info, InventoryMenuPage instance, Map<String, Object> context) {
        pendingReturns.clear();
        cancelChatPrompts();
        if (page != null) {
            for (Map.Entry<String, Object> entry : page.ctx.data().entrySet()) {
                context.putIfAbsent(entry.getKey(), entry.getValue());
            }
            page.ctx.data().clear();
            stack.addLast(page);
        }
        PageContext next = new PageContext();
        next.page = instance;
        int[] dimensions = info.menuAnnotation.dimensions().clone();
        next.type = info.menuAnnotation.type();
        String title = Messaging.parseComponents(Messaging.tryTranslate(
                context.containsKey("title") ? (String) context.get("title") : info.menuAnnotation.title()));
        Container container = instance.createContainer(title);
        int size;
        if (container == null) {
            size = next.type.getSize(dimensions);
            container = new SimpleContainer(size);
        } else {
            size = container.getContainerSize();
        }
        next.columns = next.type == InventoryType.CHEST ? 9 : next.type.getColumns();
        next.rows = Math.max(1, (int) Math.ceil((double) size / next.columns));
        next.container = container;
        next.slots = new InventoryMenuSlot[size];
        next.ctx = new MenuContext(this, next.slots, container, title, context);
        page = next;

        for (Bindable<MenuSlot> slotInfo : info.slots) {
            InventoryMenuSlot slot = createSlot(indexOf(next, slotInfo.data.slot()), slotInfo.data);
            slotInfo.bind(instance, slot);
        }
        List<InventoryMenuTransition> transitions = new ArrayList<>();
        for (Bindable<MenuTransition> transitionInfo : info.transitions) {
            InventoryMenuTransition transition = new InventoryMenuTransition(
                    next.ctx.getSlot(indexOf(next, transitionInfo.data.pos())), transitionInfo.data.value());
            transitionInfo.bind(instance, transition);
            transitions.add(transition);
        }
        next.patterns = new InventoryMenuPattern[info.patterns.length];
        for (int i = 0; i < info.patterns.length; i++) {
            InventoryMenuPattern pattern = parsePattern(next, transitions, info.patterns[i]);
            info.patterns[i].bind(instance, pattern);
            next.patterns[i] = pattern;
        }
        next.transitions = transitions.toArray(new InventoryMenuTransition[0]);
        info.inject(instance, next.ctx.data());
        instance.initialise(next.ctx);
        for (Invokable<ClickHandler> invokable : info.clickHandlers) {
            int index = indexOf(next, invokable.data.slot());
            if (index < 0 || index >= size) {
                continue;
            }
            InventoryMenuSlot slot = next.ctx.getSlot(index);
            slot.addClickHandler(event -> {
                if (event.getSlot() != index)
                    return;
                if (!accepts(event.getAction(), invokable.data.filter())) {
                    event.setCancelled(true);
                    return;
                }
                try {
                    invokable.method.invoke(instance, slot, event);
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            });
        }
        reopenAll();
    }

    private static boolean accepts(InventoryAction needle, InventoryAction[] haystack) {
        if (haystack.length == 0)
            return true;
        for (InventoryAction action : haystack) {
            if (needle == action)
                return true;
        }
        return false;
    }

    /** @return the page a click on this slot moves to, or null when it carries no transition */
    private Class<? extends InventoryMenuPage> destinationOf(InventoryMenuSlot slot) {
        for (InventoryMenuTransition transition : page.transitions) {
            Class<? extends InventoryMenuPage> next = transition.accept(slot);
            if (next != null)
                return next;
        }
        return null;
    }

    private InventoryMenuSlot createSlot(int index, MenuSlot data) {
        InventoryMenuSlot slot = page.ctx.getSlot(index);
        slot.initialise(data);
        return slot;
    }

    /** Translates a {@code {row, column}} annotation position into a container index. */
    private static int indexOf(PageContext page, int[] pos) {
        return pos[0] * page.columns + pos[1];
    }

    private void openFor(ServerPlayer player) {
        PageContext current = page;
        String initial = current.page instanceof InputMenus.ChatStringInputPage input ? input.initialValue() : null;
        if (current.page instanceof InputMenus.ChatStringInputPage input) {
            if (!(input instanceof InputMenus.StringInputPage) || !CitizensAnvilMenu.canRepresent(initial)) {
                askInChat(player, input, initial);
                return;
            }
        }
        int size = current.container.getContainerSize();
        MenuType<?> type = current.type.toMenuType(size);
        Component title = Messaging.minecraftComponentFromRawMessage(current.ctx.getTitle());
        MenuProvider provider = new MenuProvider() {
            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player who) {
                AbstractContainerMenu container = current.page instanceof InputMenus.StringInputPage input
                        ? new CitizensAnvilMenu(containerId, inventory, InventoryMenu.this, input, initial)
                        : new CitizensMenuContainer(type, containerId, inventory,
                                current.container, current.rows, current.columns, InventoryMenu.this);
                viewers.put(player, container);
                return container;
            }

            @Override
            public Component getDisplayName() {
                return title;
            }
        };
        player.openMenu(provider);
    }

    /**
     * Runs a text-input page as a chat question. Re-asks until the value is accepted, which is what upstream's anvil page
     * does by staying open.
     */
    private void askInChat(ServerPlayer player, InputMenus.ChatStringInputPage input, String initial) {
        cancelChatPrompt(player);
        player.closeContainer();
        PageContext current = page;
        viewers.put(player, player.inventoryMenu);
        Runnable[] registration = new Runnable[1];
        registration[0] = InputMenus.prompt(player, input.getPrompt(initial),
                () -> chatPrompts.get(player) == registration[0] && page == current
                        && isCurrent(player.inventoryMenu, player), answer -> {
            cancelChatPrompt(player);
            boolean accepted = input.accept(answer.isEmpty() ? null : answer);
            if (page != current || !isCurrent(player.inventoryMenu, player))
                return;
            if (accepted) {
                transitionBack();
            } else {
                askInChat(player, input, answer);
            }
        });
        chatPrompts.put(player, registration[0]);
    }

    private void cancelChatPrompt(ServerPlayer player) {
        Runnable cancel = chatPrompts.remove(player);
        if (cancel != null) {
            cancel.run();
        }
    }

    private void cancelChatPrompts() {
        for (Runnable cancel : chatPrompts.values()) {
            cancel.run();
        }
        chatPrompts.clear();
    }

    /**
     * Expands a drawn pattern into concrete slots and transitions. Each character is looked up among the pattern's own
     * slot and transition definitions; a newline (literal or escaped) starts the next row.
     */
    private InventoryMenuPattern parsePattern(PageContext page, List<InventoryMenuTransition> transitions,
            Bindable<MenuPattern> patternInfo) {
        String pattern = patternInfo.data.value();
        Map<Character, MenuSlot> slotMap = new HashMap<>();
        for (MenuSlot slot : patternInfo.data.slots()) {
            slotMap.put(slot.pat(), slot);
        }
        Map<Character, MenuTransition> transitionMap = new HashMap<>();
        for (MenuTransition transition : patternInfo.data.transitions()) {
            transitionMap.put(transition.pat(), transition);
        }
        List<InventoryMenuSlot> patternSlots = new ArrayList<>();
        List<InventoryMenuTransition> patternTransitions = new ArrayList<>();
        int row = 0;
        int col = 0;
        int size = page.container.getContainerSize();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\n' || c == '\\' && i + 1 < pattern.length() && pattern.charAt(i + 1) == 'n') {
                if (c != '\n') {
                    i++;
                }
                row++;
                col = 0;
                continue;
            }
            // the offset is read fresh each time: upstream mutates the annotation array in place, which corrupts every
            // later use of the same annotation
            int[] offset = patternInfo.data.offset();
            int index = indexOf(page, new int[] { offset[0] + row, offset[1] + col });
            if (index >= 0 && index < size) {
                MenuSlot slot = slotMap.get(c);
                if (slot != null) {
                    patternSlots.add(createSlot(index, slot));
                }
                MenuTransition transition = transitionMap.get(c);
                if (transition != null) {
                    InventoryMenuTransition concrete = new InventoryMenuTransition(page.ctx.getSlot(index),
                            transition.value());
                    patternTransitions.add(concrete);
                    transitions.add(concrete);
                }
            }
            col++;
        }
        return new InventoryMenuPattern(patternInfo.data, patternSlots, patternTransitions);
    }

    /**
     * Moves every viewer onto the current page. Minecraft ties a container to the screen the client opened, so the only
     * way to change what a player is looking at is to close and reopen; the items live in the shared container and are
     * unaffected.
     */
    private void reopenAll() {
        if (viewers.isEmpty())
            return;
        transitioning = true;
        List<ServerPlayer> existing = new ArrayList<>(viewers.keySet());
        Map<ServerPlayer, AbstractContainerMenu> previous = new HashMap<>(viewers);
        viewers.clear();
        for (ServerPlayer player : existing) {
            if (player.containerMenu == previous.get(player)) {
                player.closeContainer();
            } else {
                continue;
            }
            if (!player.isRemoved() && !player.hasDisconnected()) {
                openFor(player);
            }
        }
        transitioning = false;
    }

    /** A field/method/class annotation together with the setter that injects the concrete object, if any. */
    private static class Bindable<T> {
        private final MethodHandle bind;
        private final T data;

        private Bindable(MethodHandle bind, T data) {
            this.bind = bind;
            this.data = data;
        }

        private void bind(Object instance, Object value) {
            if (bind == null)
                return;
            try {
                bind.invoke(instance, value);
            } catch (Throwable ex) {
                ex.printStackTrace();
            }
        }
    }

    /** Everything reflection can tell us about a page class, worked out once and cached. */
    private static class InventoryMenuInfo {
        private final Invokable<ClickHandler>[] clickHandlers;
        private Constructor<? extends InventoryMenuPage> constructor;
        private final Map<String, MethodHandle> injectables;
        private Menu menuAnnotation;
        private final Bindable<MenuPattern>[] patterns;
        private final Bindable<MenuSlot>[] slots;
        private final Bindable<MenuTransition>[] transitions;

        private InventoryMenuInfo(Class<?> clazz) {
            patterns = bindables(clazz, MenuPattern.class, InventoryMenuPattern.class);
            slots = bindables(clazz, MenuSlot.class, InventoryMenuSlot.class);
            transitions = bindables(clazz, MenuTransition.class, InventoryMenuTransition.class);
            clickHandlers = clickHandlers(clazz);
            injectables = injectables(clazz);
        }

        @SuppressWarnings("unchecked")
        private <T extends Annotation> Bindable<T>[] bindables(Class<?> clazz, Class<T> annotationType,
                Class<?> concreteType) {
            List<Bindable<T>> bindables = new ArrayList<>();
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                MethodHandle bind = null;
                if (field.getType() == concreteType) {
                    try {
                        bind = LOOKUP.unreflectSetter(field);
                    } catch (IllegalAccessException ex) {
                        ex.printStackTrace();
                    }
                }
                for (T annotation : field.getAnnotationsByType(annotationType)) {
                    bindables.add(new Bindable<>(bind, annotation));
                }
            }
            List<AccessibleObject> members = new ArrayList<>();
            members.addAll(Arrays.asList(clazz.getDeclaredConstructors()));
            members.addAll(Arrays.asList(clazz.getDeclaredMethods()));
            for (AccessibleObject member : members) {
                member.setAccessible(true);
                for (T annotation : member.getAnnotationsByType(annotationType)) {
                    bindables.add(new Bindable<>(null, annotation));
                }
            }
            for (T annotation : clazz.getAnnotationsByType(annotationType)) {
                bindables.add(new Bindable<>(null, annotation));
            }
            return bindables.toArray(new Bindable[0]);
        }

        @SuppressWarnings("unchecked")
        private Invokable<ClickHandler>[] clickHandlers(Class<?> clazz) {
            List<Invokable<ClickHandler>> invokables = new ArrayList<>();
            for (Method method : clazz.getDeclaredMethods()) {
                method.setAccessible(true);
                try {
                    for (ClickHandler handler : method.getAnnotationsByType(ClickHandler.class)) {
                        invokables.add(new Invokable<>(handler, LOOKUP.unreflect(method)));
                    }
                    // a @MenuSlot on a method means "this method handles that slot", with no action filter
                    for (MenuSlot slot : method.getAnnotationsByType(MenuSlot.class)) {
                        invokables.add(new Invokable<>(handlerFor(slot), LOOKUP.unreflect(method)));
                    }
                } catch (IllegalAccessException ex) {
                    ex.printStackTrace();
                }
            }
            return invokables.toArray(new Invokable[0]);
        }

        private InventoryMenuPage createInstance() {
            if (constructor == null)
                throw new IllegalStateException("menu page " + menuAnnotation + " has no no-argument constructor");
            try {
                return constructor.newInstance();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }

        private void inject(Object instance, Map<String, Object> data) {
            for (Map.Entry<String, MethodHandle> entry : injectables.entrySet()) {
                Object value = data.get(entry.getKey());
                if (value == null) {
                    continue;
                }
                try {
                    entry.getValue().invoke(instance, value);
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            }
        }

        private Map<String, MethodHandle> injectables(Class<?> clazz) {
            Map<String, MethodHandle> injectables = new HashMap<>();
            for (Field field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(InjectContext.class)) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    injectables.put(field.getName(), LOOKUP.unreflectSetter(field));
                } catch (IllegalAccessException ex) {
                    ex.printStackTrace();
                }
            }
            return injectables;
        }

        private static ClickHandler handlerFor(MenuSlot slot) {
            return new ClickHandler() {
                @Override
                public Class<? extends Annotation> annotationType() {
                    return ClickHandler.class;
                }

                @Override
                public InventoryAction[] filter() {
                    return new InventoryAction[] {};
                }

                @Override
                public int[] slot() {
                    return slot.slot();
                }
            };
        }

        private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    }

    private static class Invokable<T> {
        private final T data;
        private final MethodHandle method;

        private Invokable(T data, MethodHandle method) {
            this.data = data;
            this.method = method;
        }
    }

    private static class PageContext {
        private int columns;
        private Container container;
        private MenuContext ctx;
        private InventoryMenuPage page;
        private InventoryMenuPattern[] patterns;
        private int rows;
        private InventoryMenuSlot[] slots;
        private InventoryMenuTransition[] transitions;
        private InventoryType type;
    }

    private static InventoryMenuInfo infoFor(Class<? extends InventoryMenuPage> clazz) {
        InventoryMenuInfo cached = CACHED_INFOS.get(clazz);
        if (cached != null)
            return cached;
        InventoryMenuInfo info = new InventoryMenuInfo(clazz);
        info.menuAnnotation = clazz.getAnnotation(Menu.class);
        if (info.menuAnnotation == null)
            throw new IllegalArgumentException(clazz.getName() + " is missing its @Menu annotation");
        try {
            Constructor<? extends InventoryMenuPage> found = clazz.getDeclaredConstructor();
            found.setAccessible(true);
            info.constructor = found;
        } catch (NoSuchMethodException ex) {
            // fine: only pages reached through a transition need to be constructible reflectively
        }
        CACHED_INFOS.put(clazz, info);
        return info;
    }

    /** Creates a menu starting at the given page class, which must have a no-argument constructor. */
    public static InventoryMenu create(Class<? extends InventoryMenuPage> clazz) {
        return createWithContext(clazz, new HashMap<>());
    }

    /** Creates a menu starting at an already-built page, which may take constructor arguments. */
    public static InventoryMenu create(InventoryMenuPage instance) {
        return new InventoryMenu(infoFor(instance.getClass()), instance);
    }

    public static InventoryMenu createWithContext(Class<? extends InventoryMenuPage> clazz,
            Map<String, Object> context) {
        return new InventoryMenu(infoFor(clazz), context);
    }

    /**
     * Upstream registers the menu as a Bukkit listener here and unregisters it on close. This port needs no registration
     * — {@link CitizensMenuContainer} calls the menu directly — so this is {@link #create} under another name, kept so
     * that call sites port across unchanged.
     */
    public static InventoryMenu createSelfRegistered(Class<? extends InventoryMenuPage> clazz) {
        return create(clazz);
    }

    public static InventoryMenu createSelfRegistered(InventoryMenuPage instance) {
        return create(instance);
    }

    private static final Map<Class<? extends InventoryMenuPage>, InventoryMenuInfo> CACHED_INFOS = new WeakHashMap<>();
}
