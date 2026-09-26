package net.citizensnpcs.util;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.world.entity.Entity;

/** Native riding operations whose owner can be superseded by mount-event callbacks. */
public final class NPCMounts {
    private static final ThreadLocal<Request> REQUEST = new ThreadLocal<>();

    private NPCMounts() {
    }

    public static boolean dismount(Entity rider, BooleanSupplier current) {
        if (rider == null || !current.getAsBoolean())
            return false;
        Entity vehicle = rider.getVehicle();
        if (vehicle == null)
            return true;
        return perform(new Request(rider, vehicle, false, current), () -> {
            rider.stopRiding();
            return rider.getVehicle() == null && current.getAsBoolean();
        });
    }

    public static boolean mount(Entity rider, Entity vehicle, BooleanSupplier current) {
        if (!canMount(rider, vehicle) || !current.getAsBoolean())
            return false;
        NPC riderNPC = NPCRegistries.lookup(rider), vehicleNPC = NPCRegistries.lookup(vehicle);
        var level = rider.level();
        BooleanSupplier valid = () -> current.getAsBoolean() && owns(riderNPC, rider) && owns(vehicleNPC, vehicle)
                && rider.level() == level && vehicle.level() == level && canMount(rider, vehicle);
        if (rider.getVehicle() == vehicle)
            return true;
        return perform(new Request(rider, vehicle, true, valid),
                () -> rider.startRiding(vehicle, true) && valid.getAsBoolean() && rider.getVehicle() == vehicle);
    }

    private static boolean canMount(Entity rider, Entity vehicle) {
        if (rider == null || vehicle == null || rider.isRemoved() || vehicle.isRemoved() || rider == vehicle
                || rider.level() != vehicle.level())
            return false;
        Set<Entity> visited = new HashSet<>();
        for (Entity ancestor = vehicle; ancestor != null; ancestor = ancestor.getVehicle()) {
            if (ancestor == rider || !visited.add(ancestor))
                return false;
        }
        return true;
    }

    private static boolean owns(NPC npc, Entity entity) {
        return !entity.isRemoved() && (npc == null || npc.isSpawned() && npc.getEntity() == entity);
    }

    private static boolean perform(Request request, BooleanSupplier operation) {
        Request previous = REQUEST.get();
        REQUEST.set(request);
        try {
            return operation.getAsBoolean();
        } finally {
            if (previous == null) REQUEST.remove();
            else REQUEST.set(previous);
        }
    }

    /** Called around NeoForge's complete event dispatch, before native riding fields are changed. */
    public static boolean dispatch(Entity rider, Entity vehicle, boolean mounting, BooleanSupplier nativeEvent) {
        Request request = REQUEST.get();
        if (request == null || request.rider != rider || request.vehicle != vehicle || request.mounting != mounting)
            return nativeEvent.getAsBoolean();
        Entity previousVehicle = rider.getVehicle();
        return nativeEvent.getAsBoolean() && request.current.getAsBoolean() && rider.getVehicle() == previousVehicle;
    }

    /** Keep native mount-before-dismount event order while guarding the nested detachment. */
    public static void duringMountDismount(Entity rider, Entity vehicle, Runnable nativeDismount) {
        Request request = REQUEST.get();
        if (request == null || request.rider != rider || request.vehicle != vehicle || !request.mounting) {
            nativeDismount.run();
            return;
        }
        perform(new Request(rider, rider.getVehicle(), false, request.current), () -> {
            nativeDismount.run();
            return true;
        });
    }

    /** Native startRiding does not itself check whether its stopRiding call was canceled. */
    public static boolean canCompleteMount(Entity rider, Entity vehicle) {
        Request request = REQUEST.get();
        return request == null || request.rider != rider || request.vehicle != vehicle || !request.mounting
                || request.current.getAsBoolean() && rider.getVehicle() == null;
    }

    /** LivingEntity's post-dismount placement must not detach a newer vehicle selected by an event listener. */
    public static boolean shouldPlaceAfterDismount(Entity rider, Entity oldVehicle) {
        Request request = REQUEST.get();
        return request == null || request.rider != rider || request.vehicle != oldVehicle || request.mounting
                || rider.getVehicle() == null;
    }

    public static boolean shouldRemoveVehicleEffects(Entity rider) {
        Request request = REQUEST.get();
        return request == null || request.rider != rider || request.mounting || rider.getVehicle() != request.vehicle;
    }

    private record Request(Entity rider, Entity vehicle, boolean mounting, BooleanSupplier current) {
    }
}
