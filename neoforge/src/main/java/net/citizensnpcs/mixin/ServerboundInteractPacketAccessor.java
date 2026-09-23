package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;

@Mixin(ServerboundInteractPacket.class)
public interface ServerboundInteractPacketAccessor {
    @Accessor("entityId")
    int citizens$getEntityId();
}
