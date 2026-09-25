package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;

/** Packet IDs also identify virtual entities, which cannot be resolved through Level.getEntity. */
@Mixin({ ClientboundMoveEntityPacket.class, ClientboundRotateHeadPacket.class })
public interface RotationEntityIdAccessor {
    @Accessor("entityId") int citizens$entityId();
}
