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
import java.util.function.BooleanSupplier;

/** Native tickPassenger otherwise ticks player subclasses even when they have never entered the world. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPassengerMixin {
    @Shadow private void tickPassenger(Entity vehicle, Entity passenger) { throw new AssertionError(); }

    @Inject(method = "tick", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void citizens$tickPassengersOfVirtualRoots(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        for (Entity root : PacketNPC.getRootEntities(level)) {
            if (!PacketNPC.isPacketEntity(root) || root.getVehicle() != null || root.level() != level) continue;
            if (!level.isPositionEntityTicking(root.blockPosition()) || level.tickRateManager().isEntityFrozen(root)) continue;
            // World iteration skips mounted entities. A virtual root is absent from that iteration, so its real
            // passengers otherwise never rideTick. Enter only the native passenger traversal; never tick the root.
            level.guardEntityTick(vehicle -> {
                for (Entity child : vehicle.getPassengers()) tickPassenger(vehicle, child);
            }, root);
        }
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void citizens$positionVirtualPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (!PacketNPC.isPacketEntity(passenger) || passenger.getVehicle() != vehicle) return;
        PacketMounts.position(passenger);
        for (Entity child : passenger.getPassengers()) tickPassenger(passenger, child);
        ci.cancel();
    }
}
