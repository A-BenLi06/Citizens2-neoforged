package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;

@Mixin(ClientboundSetPassengersPacket.class)
public interface SetPassengersPacketAccessor {
    /** Only used on a fresh packet, never the shared native broadcast. */
    @Mutable
    @Accessor("passengers")
    void citizens$setPassengers(int[] passengers);
}
