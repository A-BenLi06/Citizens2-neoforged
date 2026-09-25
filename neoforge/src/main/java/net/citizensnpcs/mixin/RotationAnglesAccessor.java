package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;

/** Set angles only on a private copy decoded through the native stream codec. */
@Mixin({ ClientboundAddEntityPacket.class, ClientboundTeleportEntityPacket.class })
public interface RotationAnglesAccessor {
    @Mutable @Accessor("yRot") void citizens$yaw(byte yaw);
    @Mutable @Accessor("xRot") void citizens$pitch(byte pitch);
}
