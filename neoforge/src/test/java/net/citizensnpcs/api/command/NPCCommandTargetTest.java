package net.citizensnpcs.api.command;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;

class NPCCommandTargetTest {
    private static final String COMMAND = "npc rename changed --id 3";
    private static String context() { return COMMAND; }

    private NPC target(AtomicReference<NPC> owner) {
        NPCRegistry registry = (NPCRegistry) Proxy.newProxyInstance(NPCRegistry.class.getClassLoader(),
                new Class<?>[] { NPCRegistry.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getById", "getByUniqueId" -> owner.get();
                    default -> throw new AssertionError(method.getName());
                });
        UUID uuid = UUID.randomUUID();
        NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] { NPC.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> 3;
                    case "getUniqueId" -> uuid;
                    case "getOwningRegistry" -> registry;
                    default -> throw new AssertionError(method.getName());
                });
        owner.set(npc);
        return npc;
    }

    @Test void scopesAreConsumedOnceAndRestoreAcrossNestedFailures() {
        NPC first = target(new AtomicReference<>()), second = target(new AtomicReference<>());
        NPCCommandTarget.dispatch(first, COMMAND, () -> {
            NPCCommandTarget.dispatch(null, COMMAND, () -> assertNull(NPCCommandTarget.consume(context())));
            assertThrows(IllegalStateException.class, () -> NPCCommandTarget.dispatch(second, COMMAND, () -> {
                assertSame(second, NPCCommandTarget.consume(context()));
                throw new IllegalStateException("nested command failed");
            }));
            assertNull(NPCCommandTarget.consume("npc rename other --id 3"));
            assertSame(first, NPCCommandTarget.consume(context()));
            assertNull(NPCCommandTarget.consume(context()));
        });
        assertNull(NPCCommandTarget.consume(context()));
    }

    @Test void retiredOrReplacedTargetsNeverDispatch() {
        var owner = new AtomicReference<NPC>();
        NPC old = target(owner);
        owner.set(null);
        NPCCommandTarget.dispatch(old, COMMAND, () -> fail("retired target dispatched"));
        target(owner);
        NPCCommandTarget.dispatch(old, COMMAND, () -> fail("replacement target dispatched"));
    }
}
