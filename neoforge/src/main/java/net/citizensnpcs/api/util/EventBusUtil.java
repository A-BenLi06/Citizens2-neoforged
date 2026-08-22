package net.citizensnpcs.api.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Registers objects on the NeoForge event bus, skipping those with no handlers.
 * <p>
 * Bukkit's {@code registerEvents} silently accepts a listener that declares no handler methods, and upstream relies on
 * that: {@code AbstractNPC.addTrait} registers every trait unconditionally, even though most traits handle no events
 * at all. NeoForge's bus instead throws {@code IllegalArgumentException} when an object has no {@code @SubscribeEvent}
 * method, which would make creating an NPC fail outright.
 * <p>
 * Note that a trait's own event handlers are declared with {@code @TraitEventHandler} and wired separately by
 * {@code TraitInfo.registerListener}; {@code @SubscribeEvent} methods on a trait are the rarer case, and this keeps
 * those working without breaking everything else.
 */
public class EventBusUtil {
    private EventBusUtil() {
    }

    /**
     * @return whether the object was registered, i.e. whether it declares any {@code @SubscribeEvent} method
     */
    public static boolean register(Object target) {
        if (target == null || !hasSubscribeEventMethods(target.getClass()))
            return false;
        NeoForge.EVENT_BUS.register(target);
        return true;
    }

    /** Unregistering something never registered is harmless, so this needs no matching guard. */
    public static void unregister(Object target) {
        if (target != null) {
            NeoForge.EVENT_BUS.unregister(target);
        }
    }

    private static boolean hasSubscribeEventMethods(Class<?> clazz) {
        return HAS_HANDLERS.computeIfAbsent(clazz, c -> {
            for (Class<?> search = c; search != null && search != Object.class; search = search.getSuperclass()) {
                for (Method method : search.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(SubscribeEvent.class))
                        return true;
                }
            }
            return false;
        });
    }

    private static final Map<Class<?>, Boolean> HAS_HANDLERS = new ConcurrentHashMap<>();
}
