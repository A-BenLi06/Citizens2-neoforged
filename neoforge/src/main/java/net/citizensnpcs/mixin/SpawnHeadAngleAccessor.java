package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;

@Mixin(ClientboundAddEntityPacket.class)
public interface SpawnHeadAngleAccessor {
    @Mutable @Accessor("yHeadRot") void citizens$headYaw(byte yaw);
}
