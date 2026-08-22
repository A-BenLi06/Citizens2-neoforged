package net.citizensnpcs.api.trait;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.TraitEventHandler.NPCEventExtractor;
import net.citizensnpcs.api.util.Messaging;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityEvent;

/**
 * Builds a trait.
 */
public final class TraitInfo {
    private boolean defaultTrait;
    private String name;
    private TraitTemplateParser parser;
    private Supplier<? extends Trait> supplier;
    private boolean trackStats;
    private final Class<? extends Trait> trait;

    private TraitInfo(Class<? extends Trait> trait) {
        this.trait = trait;
        this.parser = TraitTemplateParser.createDefault(trait);
        TraitName anno = trait.getAnnotation(TraitName.class);
        if (anno != null) {
            name = anno.value().toLowerCase(Locale.ROOT);
        }
        try {
            Constructor<? extends Trait> cons = trait.getDeclaredConstructor();
            cons.setAccessible(true);
            supplier = () -> {
                try {
                    return cons.newInstance();
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            };
        } catch (NoSuchMethodException | SecurityException e) {
        }
    }

    public TraitInfo asDefaultTrait() {
        this.defaultTrait = true;
        return this;
    }

    public void checkValid() {
        if (supplier == null) {
            try {
                trait.getConstructor();
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("Trait class must have a no-arguments constructor");
            }
        }
    }

    public TraitTemplateParser getParser() {
        return parser;
    }

    public Class<? extends Trait> getTraitClass() {
        return trait;
    }

    public String getTraitName() {
        return name;
    }

    public boolean isDefaultTrait() {
        return defaultTrait;
    }

    public TraitInfo optInToStats() {
        this.trackStats = true;
        return this;
    }

    /**
     * Wires every {@link TraitEventHandler} method on the trait class to the NeoForge event bus.
     * <p>
     * Upstream takes a {@code Plugin} and has to reflect out each event's static {@code HandlerList} field to register
     * a synthetic {@code RegisteredListener}. NeoForge's bus registers against the event class directly, so the
     * reflection and the plugin argument are both gone; the dispatch semantics are unchanged.
     */
    public void registerListener() {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Method method : trait.getDeclaredMethods()) {
            TraitEventHandler sel = method.getAnnotation(TraitEventHandler.class);
            if (sel == null)
                continue;
            if (method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                Messaging.severe("Invalid @TraitEventHandler " + method + ": expected a single Event parameter");
                continue;
            }
            final NPCEventExtractor processor;
            try {
                processor = sel.processor() != NPCEventExtractor.class
                        ? sel.processor().getDeclaredConstructor().newInstance()
                        : event -> {
                            if (event instanceof NPCEvent) {
                                return ((NPCEvent) event).getNPC();
                            } else if (event instanceof EntityEvent) {
                                return CitizensAPI.getNPCRegistry().getNPC(((EntityEvent) event).getEntity());
                            }
                            return null;
                        };
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
            @SuppressWarnings("unchecked")
            Class<Event> eventClass = (Class<Event>) method.getParameterTypes()[0];
            try {
                method.setAccessible(true);
                final MethodHandle asMethodHandle = lookup.unreflect(method);
                Consumer<Event> dispatch = event -> {
                    NPC npc = processor.apply(event);
                    if (npc == null)
                        return;
                    Trait instance = npc.getTraitNullable(trait);
                    if (instance == null)
                        return;
                    try {
                        asMethodHandle.invoke(instance, event);
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                };
                NeoForge.EVENT_BUS.addListener(sel.priority(), sel.receiveCanceled(), eventClass, dispatch);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }
        }
    }

    public boolean shouldTrackStats() {
        return trackStats;
    }

    @SuppressWarnings("unchecked")
    public <T extends Trait> T tryCreateInstance() {
        return (T) supplier.get();
    }

    public TraitInfo withName(String name) {
        Objects.requireNonNull(name);
        this.name = name.toLowerCase(Locale.ROOT);
        return this;
    }

    public TraitInfo withSupplier(Supplier<? extends Trait> supplier) {
        this.supplier = supplier;
        return this;
    }

    public TraitInfo withTemplateParser(TraitTemplateParser parser) {
        Objects.requireNonNull(parser);
        this.parser = parser;
        return this;
    }

    /**
     * Constructs a factory with the given trait class. The trait class must have a no-arguments constructor.
     *
     * @param trait
     *            Class of the trait
     * @return The created {@link TraitInfo}
     * @throws IllegalArgumentException
     *             If the trait class does not have a no-arguments constructor
     */
    public static TraitInfo create(Class<? extends Trait> trait) {
        Objects.requireNonNull(trait);
        return new TraitInfo(trait);
    }
}
