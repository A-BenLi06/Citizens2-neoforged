package net.yuuniverse.interactions;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Fixture-only native remainder callback counts; excluded from ordinary source sets and release JARs. */
@EventBusSubscriber(modid="interactions",bus=EventBusSubscriber.Bus.MOD)
public final class MachineRemainderItem {
    static Item item;
    static int calls;
    static boolean fail;
    static boolean distinctRemainders;
    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(Registries.ITEM,helper->{
            item=new Item(new Item.Properties()) {
                @Override public boolean hasCraftingRemainingItem(ItemStack stack) { return true; }
                @Override public ItemStack getCraftingRemainingItem(ItemStack stack) {
                    calls++;
                    if(fail) throw new IllegalStateException("Expected native machine remainder failure");
                    var remainder = new ItemStack(Items.BUCKET);
                    if (distinctRemainders) remainder.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                            net.minecraft.network.chat.Component.literal("Audit remainder " + calls));
                    return remainder;
                }
            };
            helper.register(ResourceLocation.parse("interactions:audit_machine_remainder"),item);
        });
    }
}
