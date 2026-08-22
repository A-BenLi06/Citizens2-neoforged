package net.citizensnpcs.trait;

import java.util.Locale;
import java.util.function.Function;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.util.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Lets a player ride an NPC and steer it.
 * <p>
 * Right-clicking mounts, and from then on the rider's movement keys drive the NPC: on the ground, in the air, or
 * pointed wherever the rider is looking, depending on the control scheme.
 */
@TraitName("controllable")
public class Controllable extends Trait {
    private MovementController controller;
    @Persist
    private BuiltInControls controls;
    @Persist
    private boolean enabled = true;
    @Persist("owner_required")
    private boolean ownerRequired;

    public Controllable() {
        super("controllable");
    }

    /** The rider, if there is one. */
    private ServerPlayer getFirstPlayer() {
        if (!npc.isSpawned())
            return null;
        for (Entity passenger : npc.getEntity().getPassengers()) {
            if (passenger instanceof ServerPlayer player)
                return player;
        }
        return null;
    }

    public MovementController getController() {
        return controller;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Attempts to mount the player onto the NPC.
     *
     * @return whether the mount was successful
     */
    public boolean mount(ServerPlayer toMount) {
        if (!npc.isSpawned() || npc.getEntity().getPassengers().contains(toMount))
            return false;
        return toMount.startRiding(npc.getEntity(), true);
    }

    @TraitEventHandler
    public void onRightClick(NPCRightClickEvent event) {
        if (!enabled || event.getNPC() != npc)
            return;
        ServerPlayer clicker = event.getClicker();
        String typeName = BuiltInRegistries.ENTITY_TYPE.getKey(npc.getEntity().getType()).getPath()
                .toLowerCase(Locale.ROOT);
        if (!PermissionUtil.hasPermission(clicker, "citizens.npc.controllable")
                || !PermissionUtil.hasPermission(clicker, "citizens.npc.controllable." + typeName)
                || ownerRequired && !npc.getOrAddTrait(Owner.class).isOwnedBy(clicker.createCommandSourceStack()))
            return;
        mount(clicker);
        controller.rightClickEntity(event);
        event.setDelayedCancellation(true);
    }

    @Override
    public void onSpawn() {
        if (controls != null) {
            controller = controls.factory.apply(npc);
            return;
        }
        Entity entity = npc.getEntity();
        if (!(entity instanceof LivingEntity) && !(entity instanceof VehicleEntity)) {
            controller = new LookAirController(npc);
        } else if (Util.isAlwaysFlyable(entity.getType())) {
            controller = new PlayerInputAirController(npc);
        } else {
            controller = new GroundController(npc);
        }
    }

    @Override
    public void run() {
        if (!enabled || !npc.isSpawned() || controller == null)
            return;
        if (npc.getNavigator().isNavigating())
            return;
        ServerPlayer player = getFirstPlayer();
        if (player == null)
            return;
        controller.run(player, ControllableInput.of(player));
    }

    public void setControls(BuiltInControls controls) {
        this.controls = controls;
        if (npc.isSpawned()) {
            onSpawn();
        }
    }

    public boolean setEnabled(boolean enabled) {
        this.enabled = enabled;
        return enabled;
    }

    /**
     * Sets whether the player attempting to mount the NPC must actually own it.
     */
    public void setOwnerRequired(boolean ownerRequired) {
        this.ownerRequired = ownerRequired;
    }

    public boolean toggle() {
        enabled = !enabled;
        if (!enabled) {
            ServerPlayer player = getFirstPlayer();
            if (player != null) {
                player.stopRiding();
            }
        }
        return enabled;
    }

    public enum BuiltInControls {
        AIR(PlayerInputAirController::new),
        GROUND(GroundController::new),
        GROUND_JUMPLESS(JumplessGroundController::new),
        LOOK_AIR(LookAirController::new);

        private final Function<NPC, MovementController> factory;

        BuiltInControls(Function<NPC, MovementController> factory) {
            this.factory = factory;
        }
    }

    /**
     * What the rider is pressing this tick.
     * <p>
     * The client sends its input to the server whenever it is riding something, and the server records it on the player;
     * upstream reads the same values through Bukkit's {@code PlayerInputEvent} where that exists and through NMS where it
     * does not.
     */
    public static class ControllableInput {
        public double forward;
        public double horizontal;
        public boolean jump;
        public boolean sneak;
        public boolean sprint;

        static ControllableInput of(ServerPlayer player) {
            ControllableInput input = new ControllableInput();
            input.forward = player.zza;
            input.horizontal = player.xxa;
            input.jump = player.jumping;
            input.sneak = player.isShiftKeyDown();
            input.sprint = player.isSprinting();
            return input;
        }
    }

    public static class GroundController implements MovementController {
        private int jumpTicks = 0;
        private final NPC npc;
        private double speed = 0.07D;

        public GroundController(NPC npc) {
            this.npc = npc;
        }

        @Override
        public void run(ServerPlayer rider, ControllableInput input) {
            Entity entity = npc.getEntity();
            boolean onGround = entity.onGround();
            float speedMod = npc.getNavigator().getDefaultParameters()
                    .modifiedSpeed(onGround ? GROUND_SPEED : AIR_SPEED);
            if (!Util.isHorse(entity.getType())) {
                speed = updateSpeed(entity, rider.getYRot(), input, speed, speedMod,
                        Setting.MAX_CONTROLLABLE_GROUND_SPEED.asDouble());
            }
            if (onGround && jumpTicks <= 0 && input.jump) {
                setVelocity(entity, entity.getDeltaMovement().x, JUMP_VELOCITY, entity.getDeltaMovement().z);
                jumpTicks = 10;
            }
            jumpTicks--;
            setMountedYaw(entity);
        }

        private static final float AIR_SPEED = 0.5F;
        private static final float GROUND_SPEED = 0.5F;
        private static final float JUMP_VELOCITY = 0.5F;
    }

    public static class JumplessGroundController implements MovementController {
        private final NPC npc;
        private double speed = 0.07D;

        public JumplessGroundController(NPC npc) {
            this.npc = npc;
        }

        @Override
        public void run(ServerPlayer rider, ControllableInput input) {
            Entity entity = npc.getEntity();
            boolean onGround = entity.onGround();
            float speedMod = npc.getNavigator().getDefaultParameters()
                    .modifiedSpeed(onGround ? GROUND_SPEED : AIR_SPEED);
            if (!Util.isHorse(entity.getType())) {
                speed = updateSpeed(entity, rider.getYRot(), input, speed, speedMod,
                        Setting.MAX_CONTROLLABLE_GROUND_SPEED.asDouble());
            }
            setMountedYaw(entity);
        }

        private static final float AIR_SPEED = 0.5F;
        private static final float GROUND_SPEED = 0.5F;
    }

    /** Flies wherever the rider is looking. Clicking pauses and unpauses. */
    public static class LookAirController implements MovementController {
        private final NPC npc;
        private boolean paused = false;

        public LookAirController(NPC npc) {
            this.npc = npc;
        }

        @Override
        public void leftClick() {
            paused = !paused;
        }

        @Override
        public void rightClick() {
            paused = !paused;
        }

        @Override
        public void run(ServerPlayer rider, ControllableInput input) {
            Entity entity = npc.getEntity();
            if (paused) {
                setVelocity(entity, entity.getDeltaMovement().x, 0.001, entity.getDeltaMovement().z);
                return;
            }
            Vec3 dir = rider.getLookAngle().scale(npc.getNavigator().getDefaultParameters().speedModifier());
            setVelocity(entity, dir.x, dir.y, dir.z);
            setMountedYaw(entity);
        }
    }

    public interface MovementController {
        /** Called when the rider left-clicks while mounted. */
        default void leftClick() {
        }

        /** Called when the rider right-clicks while mounted. */
        default void rightClick() {
        }

        /** Called when the NPC itself is right-clicked, i.e. at the moment of mounting. */
        default void rightClickEntity(NPCRightClickEvent event) {
        }

        void run(ServerPlayer rider, ControllableInput input);
    }

    /** Flies under the rider's own movement keys, jump to climb and right-click to descend. */
    public static class PlayerInputAirController implements MovementController {
        private final NPC npc;
        private boolean paused = false;
        private double speed;

        public PlayerInputAirController(NPC npc) {
            this.npc = npc;
        }

        @Override
        public void leftClick() {
            paused = !paused;
        }

        @Override
        public void rightClick() {
            Entity entity = npc.getEntity();
            setVelocity(entity, entity.getDeltaMovement().x, -0.25, entity.getDeltaMovement().z);
        }

        @Override
        public void run(ServerPlayer rider, ControllableInput input) {
            Entity entity = npc.getEntity();
            if (paused) {
                setVelocity(entity, entity.getDeltaMovement().x, 0.001, entity.getDeltaMovement().z);
                return;
            }
            speed = updateSpeed(entity, rider.getYRot(), input, speed, 1F,
                    Setting.MAX_CONTROLLABLE_FLIGHT_SPEED.asDouble());
            Vec3 velocity = entity.getDeltaMovement();
            if (input.jump) {
                velocity = new Vec3(velocity.x, 0.25, velocity.z);
            }
            setVelocity(entity, velocity.x * 1, velocity.y * 0.98, velocity.z * 1);
            setMountedYaw(entity);
        }
    }

    private static void setVelocity(Entity entity, double x, double y, double z) {
        entity.setDeltaMovement(x, y, z);
        // vanilla only sends a velocity packet for an entity whose movement changed outside its own physics, and this is
        // exactly that case: without the flag the client keeps drawing the NPC where it thinks it should be
        entity.hasImpulse = true;
    }

    /**
     * Points the NPC the way it is moving, which is what "boat controls" means: the vehicle turns to follow its velocity
     * rather than strafing sideways.
     */
    private static void setMountedYaw(Entity entity) {
        // an ender dragon's own yaw handling fights this, and it is the one mount that flies by facing
        if (entity instanceof EnderDragon || !Setting.USE_BOAT_CONTROLS.asBoolean())
            return;
        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() == 0)
            return;
        float yaw = (float) -Math.toDegrees(Math.atan2(velocity.x, velocity.z));
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
    }

    /**
     * Accelerates the NPC in the direction the rider is facing, damping and capping the result.
     *
     * @return the new base speed, which ramps up while the rider holds forward and decays when they let go
     */
    private static double updateSpeed(Entity entity, double yaw, ControllableInput input, double speed, float speedMod,
            double maxSpeed) {
        yaw = Math.toRadians(yaw);
        Vec3 velocity = entity.getDeltaMovement();
        double oldSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (input.forward > 0) {
            velocity = new Vec3(-Math.sin(yaw) * speed * speedMod, velocity.y, Math.cos(yaw) * speed * speedMod);
        }
        double strafe = speedMod * Setting.CONTROLLABLE_GROUND_DIRECTION_MODIFIER.asDouble() * input.horizontal;
        velocity = velocity
                .add(Math.sin(yaw + Math.PI / 2) * strafe, 0, -Math.cos(yaw + Math.PI / 2) * strafe)
                .scale(0.98);
        double newSpeed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (newSpeed > maxSpeed) {
            velocity = new Vec3(velocity.x * maxSpeed / newSpeed, velocity.y, velocity.z * maxSpeed / newSpeed);
            newSpeed = maxSpeed;
        }
        setVelocity(entity, velocity.x, velocity.y, velocity.z);
        if (newSpeed > oldSpeed && speed < maxSpeed)
            return Math.min(maxSpeed, speed + (maxSpeed - speed) / 50.0D);
        return Math.max(0, speed - speed / 50.0D);
    }
}
