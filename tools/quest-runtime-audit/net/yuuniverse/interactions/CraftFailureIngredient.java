package net.yuuniverse.interactions;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Fixture-only registered item: fail during actual native remainder handling, after the take event. */
@EventBusSubscriber(modid="interactions",bus=EventBusSubscriber.Bus.MOD)
public final class CraftFailureIngredient {
    static Item item;
    static boolean fail;
    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.ITEM,helper->{
            item=new Item(new Item.Properties()) {
                @Override public boolean hasCraftingRemainingItem(ItemStack stack) { return true; }
                @Override public ItemStack getCraftingRemainingItem(ItemStack stack) {
                    if(fail) throw new IllegalStateException("Expected native remainder failure");
                    return ItemStack.EMPTY;
                }
            };
            helper.register(ResourceLocation.parse("interactions:audit_failure_ingredient"),item);
        });
    }
}
