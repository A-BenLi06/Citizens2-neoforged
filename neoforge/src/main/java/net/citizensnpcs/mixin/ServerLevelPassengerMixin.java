package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.PacketMounts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Native tickPassenger otherwise ticks player subclasses even when they have never entered the world. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPassengerMixin {
    @Shadow private void tickPassenger(Entity vehicle, Entity passenger) { throw new AssertionError(); }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void citizens$positionVirtualPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (!PacketNPC.isPacketEntity(passenger) || passenger.getVehicle() != vehicle) return;
        PacketMounts.position(passenger);
        for (Entity child : passenger.getPassengers()) tickPassenger(passenger, child);
        ci.cancel();
    }
}
