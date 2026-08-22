package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.trait.shop.NPCShopAction.Transaction;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Covers the item cost/reward arithmetic. Upstream works in Bukkit stacks where an empty slot is {@code null}; here it is
 * {@link ItemStack#EMPTY}, and every "is this slot free" test had to be rewritten, so the counting is worth pinning down.
 */
public class ItemActionTest {
    @BeforeAll
    public static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    private static InventoryMultiplexer inventory(ItemStack... contents) {
        SimpleContainer container = new SimpleContainer(9);
        for (int i = 0; i < contents.length; i++) {
            container.setItem(i, contents[i]);
        }
        return new InventoryMultiplexer(container);
    }

    private static int count(InventoryMultiplexer im, net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : im.getInventory()) {
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Test
    public void countsRepeatsAcrossStacks() {
        ItemAction action = new ItemAction(new ItemStack(Items.DIAMOND, 4));
        InventoryMultiplexer im = inventory(new ItemStack(Items.DIAMOND, 5), ItemStack.EMPTY,
                new ItemStack(Items.DIAMOND, 4));
        assertEquals(2, action.getMaxRepeats(null, im));
    }

    @Test
    public void takeRemovesExactlyTheCost() {
        ItemAction action = new ItemAction(new ItemStack(Items.DIAMOND, 3));
        InventoryMultiplexer im = inventory(new ItemStack(Items.DIAMOND, 5));
        NPCShopStorage storage = new NPCShopStorage();

        Transaction txn = action.take(storage, null, im, 1);
        assertTrue(txn.isPossible());
        txn.run();
        assertEquals(2, count(im, Items.DIAMOND));
    }

    @Test
    public void takeIsImpossibleWithoutEnough() {
        ItemAction action = new ItemAction(new ItemStack(Items.DIAMOND, 3));
        InventoryMultiplexer im = inventory(new ItemStack(Items.DIAMOND, 2));
        assertFalse(action.take(new NPCShopStorage(), null, im, 1).isPossible());
    }

    @Test
    public void rollbackRestoresTakenItems() {
        ItemAction action = new ItemAction(new ItemStack(Items.DIAMOND, 3));
        InventoryMultiplexer im = inventory(new ItemStack(Items.DIAMOND, 5));

        Transaction txn = action.take(new NPCShopStorage(), null, im, 1);
        txn.run();
        txn.rollback();
        assertEquals(5, count(im, Items.DIAMOND));
    }

    @Test
    public void grantNeedsAFreeSlotPerItem() {
        ItemAction action = new ItemAction(new ItemStack(Items.DIAMOND, 1));
        SimpleContainer full = new SimpleContainer(1);
        full.setItem(0, new ItemStack(Items.STONE, 1));
        assertFalse(action.grant(new NPCShopStorage(), null, new InventoryMultiplexer(full), 1).isPossible());

        InventoryMultiplexer roomy = inventory(new ItemStack(Items.STONE, 1));
        Transaction txn = action.grant(new NPCShopStorage(), null, roomy, 1);
        assertTrue(txn.isPossible());
        txn.run();
        assertEquals(1, count(roomy, Items.DIAMOND));
    }

    @Test
    public void damagedItemsAreRejectedUnlessAllowed() {
        ItemStack damaged = new ItemStack(Items.DIAMOND_SWORD, 1);
        damaged.set(DataComponents.DAMAGE, 5);

        ItemAction strict = new ItemAction(new ItemStack(Items.DIAMOND_SWORD, 1));
        assertEquals(0, strict.getMaxRepeats(null, inventory(damaged.copy())));

        ItemAction lenient = new ItemAction(List.of(new ItemStack(Items.DIAMOND_SWORD, 1)));
        lenient.requireUndamaged = false;
        assertEquals(0, lenient.getMaxRepeats(null, inventory(damaged.copy())),
                "damage is a component, so a damaged sword still is not the same item as an undamaged one");
    }

    @Test
    public void repairCostIsIgnoredWhenDamageIsAllowed() {
        ItemStack repaired = new ItemStack(Items.DIAMOND_SWORD, 1);
        repaired.set(DataComponents.REPAIR_COST, 3);

        ItemAction strict = new ItemAction(new ItemStack(Items.DIAMOND_SWORD, 1));
        assertEquals(0, strict.getMaxRepeats(null, inventory(repaired.copy())),
                "an anvil-touched sword differs by repair_cost alone");

        ItemAction lenient = new ItemAction(List.of(new ItemStack(Items.DIAMOND_SWORD, 1)));
        lenient.requireUndamaged = false;
        assertEquals(1, lenient.getMaxRepeats(null, inventory(repaired.copy())));
    }

    @Test
    public void metaFilterComparesOnlyTheNamedComponent() {
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD, 1);
        named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Excalibur"));

        ItemAction action = new ItemAction(named.copy());
        action.metaFilter = List.of("custom_name");

        ItemStack sameNameDifferentLore = named.copy();
        sameNameDifferentLore.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                List.of(net.minecraft.network.chat.Component.literal("a line the shop should not care about"))));
        assertEquals(1, action.getMaxRepeats(null, inventory(sameNameDifferentLore)),
                "the filter names custom_name, so lore must not matter");

        ItemStack unnamed = new ItemStack(Items.DIAMOND_SWORD, 1);
        assertEquals(0, action.getMaxRepeats(null, inventory(unnamed)));

        ItemStack differentName = new ItemStack(Items.DIAMOND_SWORD, 1);
        differentName.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Durendal"));
        assertEquals(0, action.getMaxRepeats(null, inventory(differentName)));
    }

    @Test
    public void damageRuleStillAppliesUnderAMetaFilter() {
        ItemStack named = new ItemStack(Items.DIAMOND_SWORD, 1);
        named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Excalibur"));

        ItemAction action = new ItemAction(named.copy());
        action.metaFilter = List.of("custom_name");

        ItemStack damaged = named.copy();
        damaged.set(DataComponents.DAMAGE, 12);
        assertEquals(0, action.getMaxRepeats(null, inventory(damaged.copy())),
                "a damaged item is skipped before the filter is consulted");

        action.requireUndamaged = false;
        assertEquals(1, action.getMaxRepeats(null, inventory(damaged.copy())));
    }

    @Test
    public void limitedStorageKeepsWhatItIsSold() {
        NPCShopStorage storage = new NPCShopStorage();
        storage.setUnlimited(false);
        ItemAction action = new ItemAction(new ItemStack(Items.DIAMOND, 2));
        InventoryMultiplexer im = inventory(new ItemStack(Items.DIAMOND, 2));

        Transaction txn = action.take(storage, null, im, 1);
        assertTrue(txn.isPossible());
        txn.run();
        assertEquals(0, count(im, Items.DIAMOND));
        assertEquals(1, storage.getInventory().size(), "upstream drops the items here: transact provides no free slot");
        assertEquals(2, storage.getInventory().get(0).getCount());
    }
}
