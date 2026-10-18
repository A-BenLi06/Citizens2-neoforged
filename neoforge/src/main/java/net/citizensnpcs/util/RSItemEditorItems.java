package net.citizensnpcs.util;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;

/** Live references to the optional provider's public storage API. Returned stacks are independent snapshots. */
public final class RSItemEditorItems {
    private static final String ROOT = "org.resonaresmp.rsitemeditor.";
    private RSItemEditorItems() { }
    public enum Scope { SHARED, PERSONAL }
    public record Reference(Scope scope, String id) {
        public Reference {
            java.util.Objects.requireNonNull(scope, "scope");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Saved-item ID is required");
        }
    }

    public static ItemStack snapshot(ServerPlayer actor, Reference reference) {
        return invoke(() -> { active(actor); return read(actor.createCommandSourceStack(), actor, reference, "get"); });
    }

    public static List<String> ids(ServerPlayer actor, Scope scope) {
        return invoke(() -> {
            active(actor);
            permission(actor.createCommandSourceStack(), scope, "list");
            Object store = store(actor, scope);
            @SuppressWarnings("unchecked") var ids = (List<String>) Class.forName(ROOT + "storage.NbtItemStore")
                    .getMethod("ids").invoke(store);
            return List.copyOf(ids);
        });
    }

    /** Shared grants use native give authority; personal access is restricted to the source player's own store. */
    public static void give(CommandSourceStack source, ServerPlayer recipient, Reference reference, int amount, boolean validate) {
        invoke(() -> {
            if (amount <= 0) throw new IllegalArgumentException("Saved-item amount must be positive");
            ItemStack stack = read(source, recipient, reference, reference.scope() == Scope.SHARED ? "give" : "get");
            stack.setCount(amount);
            if (!validate) {
                Class<?> support = Class.forName(ROOT + "storage.StorageSupport");
                support.getMethod("giveOrDrop", ServerPlayer.class, ItemStack.class)
                        .invoke(support.getField("INSTANCE").get(null), recipient, stack.copy());
            }
            return null;
        });
    }

    private static ItemStack read(CommandSourceStack source, ServerPlayer owner, Reference reference, String operation)
            throws ReflectiveOperationException {
        active(owner);
        if (source == null || source.getServer() != owner.getServer()) throw new IllegalStateException("Invalid saved-item source");
        if (reference.scope() == Scope.PERSONAL && source.getPlayer() != owner)
            throw new IllegalStateException("Personal item access requires the owning player source");
        permission(source, reference.scope(), operation);
        Object store = store(owner, reference.scope());
        Class<?> type = Class.forName(ROOT + "storage.NbtItemStore");
        if (!(boolean) type.getMethod("contains", String.class).invoke(store, reference.id()))
            throw new IllegalArgumentException("Saved item does not exist: " + reference.id());
        ItemStack stack = (ItemStack) type.getMethod("get", HolderLookup.Provider.class, String.class)
                .invoke(store, owner.registryAccess(), reference.id());
        if (stack == null || stack.isEmpty()) throw new IllegalStateException("Saved item is unavailable: " + reference.id());
        return stack.copy();
    }

    private static Object store(ServerPlayer owner, Scope scope) throws ReflectiveOperationException {
        Class<?> stores = Class.forName(ROOT + "storage.Stores");
        Object instance = stores.getField("INSTANCE").get(null);
        return scope == Scope.PERSONAL ? stores.getMethod("personal", ServerPlayer.class).invoke(instance, owner)
                : stores.getMethod("server", MinecraftServer.class).invoke(instance, owner.getServer());
    }

    private static void permission(CommandSourceStack source, Scope scope, String operation) throws ReflectiveOperationException {
        Class<?> permissions = Class.forName(ROOT + "permission.Permissions");
        Object instance = permissions.getField("INSTANCE").get(null);
        Object node = permissions.getMethod(scope == Scope.SHARED ? "serverItem" : "itemStorage", String.class)
                .invoke(instance, operation);
        @SuppressWarnings("unchecked") var predicate = (Predicate<CommandSourceStack>) permissions
                .getMethod("check", PermissionNode.class).invoke(instance, node);
        if (!predicate.test(source)) throw new IllegalStateException("Saved-item permission denied");
    }

    private static void active(ServerPlayer actor) {
        if (actor == null || actor.getServer() == null || !actor.getServer().isSameThread())
            throw new IllegalStateException("Saved-item access requires the server thread and online actor");
        if (actor.hasDisconnected() || actor.getServer().getPlayerList().getPlayer(actor.getUUID()) != actor)
            throw new IllegalStateException("Saved-item actor session has retired");
    }
    private interface Query<T> { T run() throws ReflectiveOperationException; }
    private static <T> T invoke(Query<T> query) {
        try { return query.run(); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Saved-item provider failed", failure.getCause());
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            throw new IllegalStateException("The supported RSItemEditor storage API is unavailable", unavailable);
        }
    }
}
