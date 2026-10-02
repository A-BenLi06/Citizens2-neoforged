package net.citizensnpcs.mixin;

import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Uses NeoForge's native block-item capture while an armor stand emits one of its death drops. */
@Mixin(Block.class)
public interface BlockDropCaptureAccessor {
    @Accessor("capturedDrops")
    static List<ItemEntity> citizens$getCapturedDrops() { throw new AssertionError(); }

    @Accessor("capturedDrops")
    static void citizens$setCapturedDrops(List<ItemEntity> drops) { throw new AssertionError(); }
}
