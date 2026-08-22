package net.citizensnpcs.trait.shop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.persistence.PersisterRegistry;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * One half of a shop trade, or one requirement of a command: something that can be taken from a player or granted to
 * them. Money, experience, items, a permission, running a command, or a condition that must simply hold.
 * <p>
 * Everything is expressed as a {@link Transaction} rather than done immediately, so a trade with several costs can be
 * checked in full before any of it is applied and rolled back if a later part fails.
 */
public abstract class NPCShopAction implements Cloneable {
    @Override
    public NPCShopAction clone() {
        try {
            return (NPCShopAction) super.clone();
        } catch (CloneNotSupportedException ex) {
            throw new AssertionError(ex);
        }
    }

    public abstract String describe();

    /** @return how many times this action could be applied, or -1 for no limit */
    public abstract int getMaxRepeats(Entity entity, InventoryMultiplexer inventory);

    public abstract Transaction grant(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory,
            int repeats);

    public Transaction grant(ServerPlayer player, int repeats) {
        return grant(new NPCShopStorage(), player, new InventoryMultiplexer(player.getInventory()), repeats);
    }

    public abstract Transaction take(NPCShopStorage storage, Entity entity, InventoryMultiplexer inventory,
            int repeats);

    public Transaction take(ServerPlayer player, int repeats) {
        return take(new NPCShopStorage(), player, new InventoryMultiplexer(player.getInventory()), repeats);
    }

    /** How an action is edited in the shop GUI. */
    public interface GUI {
        boolean canUse(ServerPlayer entity);

        InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback);

        ItemStack createMenuItem(NPCShopAction previous);

        boolean manages(NPCShopAction action);
    }

    /**
     * A change that has been worked out but not yet made. {@link #isPossible} asks whether it can be, {@link #run} makes
     * it, and {@link #rollback} undoes it when a sibling in the same trade turns out to be impossible.
     */
    public static class Transaction {
        private final Runnable execute;
        private final Supplier<Boolean> possible;
        private final Runnable rollback;

        public Transaction(Supplier<Boolean> isPossible, Runnable execute, Runnable rollback) {
            this.possible = isPossible;
            this.execute = execute;
            this.rollback = rollback;
        }

        public boolean isPossible() {
            return possible.get();
        }

        public void rollback() {
            rollback.run();
        }

        public void run() {
            execute.run();
        }

        /** All-or-nothing: possible only if every part is, and running it runs all of them. */
        public static Transaction compose(Collection<Transaction> txn) {
            if (txn.isEmpty())
                return success();
            return create(() -> txn.stream().allMatch(t -> t == null || t.isPossible()),
                    () -> txn.forEach(Transaction::run), () -> txn.forEach(Transaction::rollback));
        }

        public static Transaction compose(Transaction... txn) {
            return compose(Arrays.asList(txn));
        }

        public static Transaction create(Supplier<Boolean> isPossible, Runnable execute, Runnable rollback) {
            return new Transaction(isPossible, execute, rollback);
        }

        public static Transaction fail() {
            return create(() -> false, () -> {
            }, () -> {
            });
        }

        public static Transaction success() {
            return create(() -> true, () -> {
            }, () -> {
            });
        }
    }

    public static Iterable<GUI> getGUIs() {
        return GUIS;
    }

    public static void register(Class<? extends NPCShopAction> clazz, String type, GUI gui) {
        REGISTRY.register(type, clazz);
        GUIS.add(gui);
    }

    private static final List<GUI> GUIS = new ArrayList<>();
    private static final PersisterRegistry<NPCShopAction> REGISTRY = PersistenceLoader
            .createRegistry(NPCShopAction.class);
}
