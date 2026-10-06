package net.citizensnpcs.api.command;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.command.exception.CommandException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

class NPCCommandTargetTest {
    private static final String COMMAND = "npc rename changed --id 3";
    private static String context() { return COMMAND; }
    private static NPC receivedTarget;
    private static String receivedText;

    public static class Fixture {
        @Command(aliases = "targetaudit", modifiers = "read", desc = "", parsePlaceholders = true,
                permission = "citizens.targetaudit")
        public static void read(CommandContext context, CommandSourceStack source, NPC npc) {
            receivedTarget = npc;
            receivedText = context.getString(1);
        }
    }

    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "Audit", Component.literal("Audit"), null, null);
    }

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

    @Test void implicitTargetDrivesPlaceholdersAndHandlerWhileOrdinarySelectionStillWorks() throws CommandException {
        var manager = new CommandManager(); manager.register(Fixture.class);
        NPC selected = target(new AtomicReference<>()), bound = target(new AtomicReference<>());
        var source = source(4);
        manager.executeWithTarget(bound, "targetaudit", new String[] { "", "", "read", "<uuid>" },
                source, source, selected);
        assertSame(bound, receivedTarget);
        assertEquals(bound.getUniqueId().toString(), receivedText);
        manager.execute("targetaudit", new String[] { "read", "<uuid>" }, source, source, selected);
        assertSame(selected, receivedTarget);
        assertEquals(selected.getUniqueId().toString(), receivedText);
    }

    @Test void retiredTargetIsRejectedBeforePlaceholderOrHandlerAccess() {
        var manager = new CommandManager(); manager.register(Fixture.class);
        var owner = new AtomicReference<NPC>();
        NPC bound = target(owner); owner.set(null);
        var source = source(4);
        receivedTarget = null;
        // <npc> would call getFullName, deliberately unsupported by this proxy.
        assertThrows(CommandException.class, () -> manager.executeWithTarget(bound, "targetaudit",
                new String[] { "read", "<npc>" }, source, source, (NPC) null));
        assertNull(receivedTarget);
    }

    @Test void commandPermissionIsCheckedBeforeTargetAndPlaceholderAccess() {
        var manager = new CommandManager(); manager.register(Fixture.class);
        NPC inaccessible = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] { NPC.class },
                (proxy, method, args) -> { throw new AssertionError("Access before permission: " + method.getName()); });
        // A missing source is denied by PermissionUtil; console sources are intentionally unrestricted.
        CommandSourceStack source = null;
        receivedTarget = null;
        assertThrows(net.citizensnpcs.api.command.exception.NoPermissionsException.class,
                () -> manager.executeWithTarget(inaccessible, "targetaudit", new String[] { "read", "<npc>" },
                        source, source, (NPC) null));
        assertNull(receivedTarget);
    }
}
