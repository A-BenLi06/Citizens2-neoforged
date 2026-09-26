package net.citizensnpcs.npc;

import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.speech.SpeechContext;
import net.citizensnpcs.api.ai.speech.Talkable;
import net.citizensnpcs.api.ai.speech.TalkableEntity;
import net.citizensnpcs.api.ai.speech.event.NPCSpeechEvent;
import net.citizensnpcs.api.CitizensPlugin;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCNeedsRespawnEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.AbstractNPC;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPC.NPCUpdate;
import net.citizensnpcs.api.npc.BlockBreaker;
import net.citizensnpcs.api.npc.BlockBreaker.BlockBreakerConfiguration;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.CurrentLocation;
import net.citizensnpcs.api.trait.trait.MobType;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.npc.ai.CitizensNavigator;
import net.citizensnpcs.npc.ai.NPCSwimming;
import net.citizensnpcs.trait.AttributeTrait;
import net.citizensnpcs.trait.DisguiseTrait;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.HologramTrait.HologramRenderer;
import net.citizensnpcs.trait.ScoreboardTrait;
import net.citizensnpcs.trait.SneakTrait;
import net.citizensnpcs.util.ChunkCoord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The concrete NPC: owns the {@link EntityController} and drives spawn, despawn and per-tick updates.
 * <p>
 * Differences from upstream, beyond the mechanical Bukkit-to-Minecraft type swap:
 * <ul>
 * <li>Every {@code NMS.xxx(entity, …)} hop is a direct call on the entity. The whole {@code NMS}/{@code NMSBridge}
 * indirection existed to bridge CraftBukkit's per-version remapped internals, which NeoForge already gives us
 * directly.</li>
 * <li>Metadata tagging via Bukkit's {@code setMetadata} is replaced by {@link NPCRegistries}.</li>
 * <li>Traits and native entity controllers own appearance/state; remaining platform work is marked at its call site.</li>
 * </ul>
 */
public class CitizensNPC extends AbstractNPC {
    private EntityController entityController;
    private boolean isUpdating;
    private final CitizensNavigator navigator = new CitizensNavigator(this);
    private int updateCounter;

    public CitizensNPC(UUID uuid, int id, String name, EntityController entityController, NPCRegistry registry,
            CitizensPlugin plugin) {
        super(uuid, id, name, registry, plugin);
        Objects.requireNonNull(entityController, "entityController cannot be null");
        this.entityController = entityController;
    }

    @Override
    public boolean despawn(DespawnReason reason) {
        if (!isSpawned() && reason != DespawnReason.DEATH) {
            Messaging.debug("Tried to despawn", this, "while already despawned, DespawnReason." + reason);
            return false;
        }
        NPCDespawnEvent event = new NPCDespawnEvent(this, reason);
        if (reason == DespawnReason.CHUNK_UNLOAD) {
            // the only reason Citizens itself pre-cancels: keeping the NPC alive forces its chunk to stay loaded
            event.setCanceled(data().get(NPC.Metadata.KEEP_CHUNK_LOADED, Setting.KEEP_CHUNKS_LOADED.asBoolean()));
        }
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled() && reason != DespawnReason.DEATH && reason != DespawnReason.REMOVAL) {
            Messaging.debug("Couldn't despawn", this, "due to event cancellation, DespawnReason." + reason);
            return false;
        }
        // an NPC that is not coming back on the next start should not stay selected either
        boolean keepSelected = hasTrait(Spawned.class) && getTraitNullable(Spawned.class).shouldSpawn();
        if (!keepSelected) {
            data().remove(SELECTORS_METADATA);
        }
        getOrAddTrait(CurrentLocation.class).setLocation(getStoredLocation());
        navigator.onDespawn();
        traits.forEach(trait -> {
            try {
                trait.onDespawn(reason);
            } catch (Throwable ex) {
                Messaging.severe("Trait", trait.getName(), "onDespawn failed for NPC", getId());
                ex.printStackTrace();
            }
        });
        Entity entity = getEntity();
        if (entity != null) {
            NPCRegistries.unlink(entity);
        }
        if (reason == DespawnReason.DEATH) {
            entityController.die();
        } else {
            entityController.remove();
        }
        return true;
    }

    @Override
    public Entity getEntity() {
        return entityController == null ? null : entityController.getEntity();
    }

    /**
     * Turns the NPC to look at a point, moving head and body together.
     * <p>
     * Upstream routes this through {@code Util.faceLocation}, which ultimately sets the same three rotation fields.
     */
    @Override
    public void faceLocation(Location location) {
        Entity entity = getEntity();
        if (entity == null || location == null || location.getWorld() != entity.level())
            return;
        double dx = location.getX() - entity.getX();
        double dy = location.getY() - (entity.getY() + entity.getEyeHeight());
        double dz = location.getZ() - entity.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        entity.setYRot(yaw);
        entity.setXRot(pitch);
        entity.setYHeadRot(yaw);
        entity.setYBodyRot(yaw);
    }

    /**
     * @return the chunk the NPC currently occupies, from its live entity if spawned and from the stored location
     *         otherwise
     */
    public ChunkCoord getChunkCoord() {
        Location at = getStoredLocation();
        return at == null ? null : new ChunkCoord(at);
    }

    @Override
    public Location getStoredLocation() {
        return isSpawned() ? Location.of(getEntity()) : getOrAddTrait(CurrentLocation.class).getLocation();
    }

    @Override
    public CitizensNavigator getNavigator() {
        return navigator;
    }

    /**
     * @return a breaker that mines the block over time, the way a player would
     * @throws IllegalStateException
     *             if the NPC is not spawned — there is nothing to hold the tool or to show the cracking animation
     */
    @Override
    public BlockBreaker getBlockBreaker(BlockPos targetBlock, BlockBreakerConfiguration config) {
        Entity entity = getEntity();
        if (entity == null || !(entity.level() instanceof ServerLevel level))
            throw new IllegalStateException("Cannot break blocks with an unspawned NPC");
        return new CitizensBlockBreaker(this, entity, level, targetBlock,
                config == null ? new BlockBreakerConfiguration() : config);
    }

    @Override
    public boolean isSpawned() {
        Entity entity = getEntity();
        if (entity == null)
            return false;
        // a packet NPC's entity is deliberately not in any level, so vanilla's removed flag says nothing about whether
        // the NPC is up - the trait's own tracker is the authority
        if (hasTrait(PacketNPC.class))
            return true;
        return !entity.isRemoved();
    }

    /**
     * Loads the NPC and, if it was spawned when the server last shut down, brings it back.
     * <p>
     * The respawn is deferred by a tick: {@code load} runs while the registry is still populating, and spawning
     * mid-iteration would mutate the collection being walked.
     */
    @Override
    public void load(DataKey key) {
        super.load(key);
        navigator.load(key.getRelative("navigator"));

        if (!getOrAddTrait(Spawned.class).shouldSpawn())
            return;
        Location location = getOrAddTrait(CurrentLocation.class).getLocation();
        if (location != null) {
            CitizensAPI.getScheduler().runTaskLater(() -> {
                // the NPC can be removed inside that one tick - a /npc remove from a load-time function does exactly
                // that - and spawning it anyway leaves an entity in the world that no NPC owns and no despawn can ever
                // reach again
                if (getOwningRegistry().getByUniqueId(getUniqueId()) != this) {
                    Messaging.debug("Skipping deferred respawn of", this, "- it was removed first");
                    return;
                }
                // An NPC whose chunk is not loaded must wait for it rather than spawn into it. Spawning anyway works -
                // vanilla will happily add the entity - but it pins that chunk's entity storage for good, pulling every
                // entity stored there onto the tick list. Across a couple of hundred NPCs on a populated world that is
                // the difference between 20 TPS and 4. EventListen's chunk-load handler brings them back.
                ChunkCoord coord = new ChunkCoord(location);
                // An NPC explicitly configured to keep its chunk loaded is the one case that may load it: that is the
                // whole point of the setting, ChunkTicketTrait takes a ticket in onSpawn, and skipping the spawn would
                // mean such an NPC never comes up again after a restart.
                if (data().get(NPC.Metadata.KEEP_CHUNK_LOADED, Setting.KEEP_CHUNKS_LOADED.asBoolean())) {
                    coord.getChunk();
                } else if (!coord.isLoaded()) {
                    Messaging.debug("Deferring spawn of", this, "until chunk", coord, "loads");
                    NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(this, location));
                    return;
                }
                spawn(location, SpawnReason.RESPAWN);
            }, 1);
        } else {
            // the dimension is not loaded yet - wait for the chunk listener to tell us it is
            NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(this, getChunkCoord()));
        }
    }

    /**
     * Swaps the controller, respawning in place if the NPC is currently spawned. Not part of the {@code NPC} interface
     * — {@link #setEntityType} is the public route.
     */
    public void setEntityController(EntityController newController) {
        Objects.requireNonNull(newController, "newController cannot be null");
        PacketNPC packet = getTraitNullable(PacketNPC.class);
        if (packet != null) {
            newController = packet.wrap(newController);
        }
        boolean wasSpawned = isSpawned();
        Location prev = null;
        if (wasSpawned) {
            prev = getStoredLocation();
            despawn(DespawnReason.PENDING_RESPAWN);
        }
        entityController = newController;
        if (wasSpawned) {
            spawn(prev, SpawnReason.RESPAWN);
        }
    }

    @Override
    public void setEntityType(EntityType<?> type) {
        getOrAddTrait(MobType.class).setType(type);
        setEntityController(EntityControllers.createForType(type));
    }

    /**
     * Persists the navigator's own parameters alongside the NPC, under a {@code navigator} subkey.
     * <p>
     * The base class enforces persistent-save policy; snapshots also include these parameters.
     */
    @Override
    protected void saveState(DataKey root, boolean strict) {
        super.saveState(root, strict);
        navigator.save(root.getRelative("navigator"));
    }

    /**
     * Marks the NPC as needing a packet refresh on the next tick.
     * <p>
     * Upstream tracks a counter here and compares it against the packet-update-delay setting in {@code isUpdating};
     * the same shape is kept so the P6 packet layer can read it.
     */
    @Override
    public void scheduleUpdate(NPCUpdate update) {
        if (update == NPCUpdate.PACKET) {
            isUpdating = true;
        }
    }

    @Override
    public boolean isUpdating(NPCUpdate update) {
        return update == NPCUpdate.PACKET && isUpdating;
    }

    @Override
    public void setSneaking(boolean sneaking) {
        getOrAddTrait(SneakTrait.class).setSneaking(sneaking);
    }

    /**
     * Sets a walk target for the vanilla move control: one step, no route. This is deliberately not pathfinding — it is
     * the counterpart of upstream's {@code NMS.setDestination}, and the behaviour tree goals use it to nudge an NPC
     * around without involving the navigator at all. For a real route use {@link #getNavigator()}.
     */
    @Override
    public void setMoveDestination(Location destination) {
        Entity entity = getEntity();
        if (!(entity instanceof Mob))
            return;
        Mob mob = (Mob) entity;
        if (destination == null) {
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        } else {
            mob.getMoveControl().setWantedPosition(destination.getX(), destination.getY(), destination.getZ(), 1);
        }
    }

    @Override
    public boolean shouldRemoveFromPlayerList() {
        return data().get(NPC.Metadata.REMOVE_FROM_PLAYERLIST, Setting.REMOVE_PLAYERS_FROM_PLAYER_LIST.asBoolean());
    }

    @Override
    public boolean shouldRemoveFromTabList() {
        return data().get(NPC.Metadata.REMOVE_FROM_TABLIST, Setting.DISABLE_TABLIST.asBoolean());
    }

    @Override
    public boolean spawn(Location at, SpawnReason reason, Consumer<Entity> callback) {
        Objects.requireNonNull(at, "location cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");
        if (getEntity() != null) {
            Messaging.debug("Tried to spawn", this, "while already spawned. SpawnReason." + reason);
            return false;
        }
        ServerLevel level = at.getWorld();
        if (level == null) {
            Messaging.debug("Tried to spawn", this, "but the level was null. SpawnReason." + reason);
            return false;
        }
        final Location location = at.clone();
        if (reason == SpawnReason.CHUNK_LOAD || reason == SpawnReason.COMMAND) {
            // force the chunk resident so addFreshEntity cannot fail on an unloaded chunk
            level.getChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
        // A persisted location below the world floor is self-perpetuating: the NPC spawns there, immediately dies to the
        // void, and the despawn writes the even lower position back to saves.yml - so every later start repeats it, and
        // the recorded y creeps downwards run after run. Whatever dropped it out of the world the first time, refusing to
        // spawn back into the void turns a permanent start-up death into a one-off recovery that says so in the log.
        if (location.getY() < level.getMinBuildHeight() || location.getY() >= level.getMaxBuildHeight()) {
            // the heightmap of an unloaded chunk answers with the world floor, which would drop the NPC inside the
            // ground and let vanilla push it back out for the rest of the run - so make sure the chunk is resident,
            // whatever the spawn reason was
            level.getChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4);
            BlockPos surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    location.getBlockPos());
            Messaging.severe("NPC", getId(), getName(), "had a stored location outside the world at y", location.getY(),
                    "- respawning at the surface", surface.getY(), "instead");
            location.setY(surface.getY());
        }
        getOrAddTrait(CurrentLocation.class).setLocation(location);
        // Trait load/attachment order must not choose a different transport than the current trait set.
        entityController = PacketNPC.unwrap(entityController);
        PacketNPC packet = getTraitNullable(PacketNPC.class);
        if (packet != null) entityController = packet.wrap(entityController);
        entityController.create(location, this);
        // Player controllers initialize all skin layers; SkinLayers applies explicit overrides during trait spawning.

        traits.forEach(trait -> {
            try {
                trait.onPreSpawn();
            } catch (Throwable ex) {
                Messaging.severe("Trait", trait.getName(), "onPreSpawn failed for NPC", getId());
                ex.printStackTrace();
            }
        });
        if (data().get(NPC.Metadata.HOLOGRAM_RENDERER) instanceof HologramTrait.HologramRenderer renderer) {
            try {
                renderer.onPreSpawn(this);
            } catch (RuntimeException | Error failure) {
                discardFailedSpawn();
                throw failure;
            }
        }
        data().set(NPC.Metadata.NPC_SPAWNING_IN_PROGRESS, true);

        entityController.spawn(location, couldSpawn -> {
            if (!couldSpawn) {
                Messaging.debug("Retrying spawn of", this, "later, SpawnReason." + reason);
                discardFailedSpawn();
                NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(this, location));
                data().remove(NPC.Metadata.NPC_SPAWNING_IN_PROGRESS);
                return;
            }
            Entity entity = getEntity();
            NPCSpawnEvent spawnEvent = new NPCSpawnEvent(this, location, reason);
            NeoForge.EVENT_BUS.post(spawnEvent);
            if (spawnEvent.isCanceled()) {
                Messaging.debug("Couldn't spawn", this, "SpawnReason." + reason, "due to event cancellation.");
                discardFailedSpawn();
                data().remove(NPC.Metadata.NPC_SPAWNING_IN_PROGRESS);
                return;
            }
            // upstream waits several ticks here for Paper to finish adding the entity to its chunk; vanilla
            // addFreshEntity is synchronous and has already reported success, so the entity is live now
            entity.moveTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            entity.setYHeadRot(location.getYaw());
            data().remove(NPC.Metadata.NPC_SPAWNING_IN_PROGRESS);

            getOrAddTrait(CurrentLocation.class).setLocation(location);
            getOrAddTrait(Spawned.class).setSpawned(true);
            navigator.onSpawn();
            traits.forEach(trait -> {
                try {
                    trait.onSpawn();
                } catch (Throwable ex) {
                    Messaging.severe("Trait", trait.getName(), "onSpawn failed for NPC", getId());
                    ex.printStackTrace();
                }
            });
            if (entity instanceof Mob) {
                ((Mob) entity).setPersistenceRequired();
            }
            if (entity instanceof LivingEntity) {
                ((LivingEntity) entity).setInvulnerable(isProtected());
            }
            updateCustomName();
            if (requiresNameHologram() && !hasTrait(HologramTrait.class)) {
                // a name too long or too colourful for a vanilla nameplate is drawn as a hologram instead
                addTrait(HologramTrait.class);
            }
            if (entity instanceof ServerPlayer || entity instanceof AbstractHorse) {
                AttributeTrait attributes = getTraitNullable(AttributeTrait.class);
                var stepHeight = ((LivingEntity) entity).getAttribute(Attributes.STEP_HEIGHT);
                if (stepHeight != null && (attributes == null || !attributes.hasAttribute(Attributes.STEP_HEIGHT)))
                    stepHeight.setBaseValue(1);
            }

            Messaging.debug("Spawned", this, "SpawnReason." + reason);
            if (callback != null) {
                callback.accept(entity);
            }
        });
        return true;
    }

    @Override
    public void teleport(Location location, net.citizensnpcs.api.util.TeleportCause cause) {
        if (!isSpawned() || location.getWorld() == null)
            return;
        long request = ++teleportRevision;
        Entity entity = getEntity();
        EntityController controller = entityController;
        location = location.clone();
        var event = new net.citizensnpcs.api.event.NPCTeleportEvent(this, location);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled() || !ownsTransfer(request, controller, entity) || !isSpawned()
                || location.getWorld() == null) return;
        // The public NPC teleport contract loads its destination, including outside currently watched chunks.
        location.getChunk();
        if (!ownsTransfer(request, controller, entity) || !isSpawned()) return;
        if (location.getWorld() == entity.level()) {
            if (EntityUtil.teleport(entity, location) != null && ownsTransfer(request, controller, entity))
                getOrAddTrait(CurrentLocation.class).setLocation(Location.of(entity));
            return;
        }
        PacketNPC packet = getTraitNullable(PacketNPC.class);
        try {
            teleportAcrossWorlds(location, request, controller, entity, packet);
        } finally {
            // A cancellation callback can replace this transfer with an ordinary same-world move. Always release
            // the temporary navigation suspension for the NPC that actually survives that callback.
            if (isSpawned()) navigator.onSpawn();
        }
    }

    private void teleportAcrossWorlds(Location location, long request, EntityController controller, Entity entity,
            PacketNPC packet) {
        navigator.onDespawn();
        if (!ownsTransfer(request, controller, entity)) return;
        if (packet != null) {
            packet.prepareTeleport();
            if (!ownsTransfer(request, controller, entity) || getTraitNullable(PacketNPC.class) != packet) return;
        }

        Entity moved;
        if (entity instanceof ServerPlayer player) {
            // Native players retain their object on dimension changes. Virtual players must also update their
            // game-mode level, but must never enter the destination world's entity/player collections.
            if (packet == null) {
                moved = EntityUtil.teleport(entity, location);
            } else {
                player.unRide();
                player.setServerLevel(location.getWorld());
                player.moveTo(location.getX(), location.getY(), location.getZ(), location.getYaw(),
                        net.minecraft.util.Mth.clamp(location.getPitch(), -90, 90));
                player.setYHeadRot(location.getYaw());
                moved = player;
            }
        } else {
            // Follow Entity.teleportTo's native copy/remove/add sequence. Adopt and link the copy BEFORE native
            // admission so join/tracking callbacks see its NPC ownership and visibility rules. Recreating the native
            // entity also rebuilds world-bound components such as Mob's PathNavigation in the destination level.
            moved = entity.getType().create(location.getWorld());
            if (moved == null) {
                if (packet != null) packet.onSpawn();
                return;
            }
            moved.restoreFrom(entity);
            moved.moveTo(location.getX(), location.getY(), location.getZ(), location.getYaw(),
                    net.minecraft.util.Mth.clamp(location.getPitch(), -90, 90));
            moved.setYHeadRot(location.getYaw());
            if (!ownsTransfer(request, controller, entity)) {
                moved.discard();
                return;
            }
            entity.unRide();
            entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
            if (!ownsTransfer(request, controller, entity)) {
                moved.discard();
                return;
            }
            NPCRegistries.unlink(entity);
            controller.replaceEntity(moved);
            NPCRegistries.link(moved, this);
            if (moved instanceof Mob mob && !useMinecraftAI())
                MobEntityController.clearGoals(mob);
            getOrAddTrait(CurrentLocation.class).setLocation(Location.of(moved));
            if (packet == null && !location.getWorld().addFreshEntity(moved)) {
                moved.discard();
                return;
            }
        }
        if (moved == null || !ownsTransfer(request, controller, moved) || moved.isRemoved()) return;
        if (packet == null && moved instanceof net.citizensnpcs.npc.entity.EntityHumanNPC human)
            human.updatePlayerListMembership();
        if (packet != null && getTraitNullable(PacketNPC.class) == packet) packet.onSpawn();
        getOrAddTrait(CurrentLocation.class).setLocation(Location.of(moved));
    }

    private long teleportRevision;

    private boolean ownsTransfer(long request, EntityController controller, Entity entity) {
        return request == teleportRevision && entityController == controller && getEntity() == entity
                && getOwningRegistry() != null && getOwningRegistry().getByUniqueId(getUniqueId()) == this;
    }

    @Override
    public void update() {
        try {
            super.update();
            if (!isSpawned())
                return;

            Entity entity = getEntity();
            if (entity instanceof Mob mob) mob.setCanPickUpLoot(data().get(NPC.Metadata.PICKUP_ITEMS, false));
            if (entity instanceof net.citizensnpcs.npc.entity.EntityHumanNPC human) human.updatePlayerListMembership();
            if (data().has(NPC.Metadata.AGGRESSIVE) && entity instanceof Mob) {
                ((Mob) entity).setAggressive(data().get(NPC.Metadata.AGGRESSIVE, false));
            }
            if (entity instanceof LivingEntity) {
                ((LivingEntity) entity).setInvulnerable(isProtected());
            }
            if (data().has(NPC.Metadata.GLOWING)) {
                entity.setGlowingTag(data().get(NPC.Metadata.GLOWING, false));
            }
            if (data().has(NPC.Metadata.SILENT)) {
                entity.setSilent(data().get(NPC.Metadata.SILENT, false));
            }
            // Vex is the one vanilla entity that turns noPhysics on for the whole of its own tick and only re-asserts
            // setNoGravity(true) at the end of it. The first tick's gravity therefore gives it a downward velocity while
            // it cannot collide, and nothing sheds that velocity afterwards: vanilla leans on the vex's own move control
            // to do it, which clearing the AI removes. Left alone it sinks through the floor and dies to the void. Only a
            // downward drift is cancelled, so /npc velocity and /npc knockback still work in every other direction.
            if (entity instanceof Vex && !navigator.isNavigating()) {
                Vec3 drift = entity.getDeltaMovement();
                if (drift.y < 0) {
                    entity.setDeltaMovement(drift.x, 0, drift.z);
                }
            }
            updateCustomNameVisibility();
            navigator.run();
            // Native Citizens strategies write velocity directly; apply water adjustments after that write.
            NPCSwimming.update(this, entity);
            updateScoreboard();
            // TODO(P6): packet updates and held-item state

            updateCounter++;
        } catch (Exception ex) {
            Messaging.severe("Exception updating NPC", getId(), ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * The entity players see and click, which is the disguise when {@link DisguiseTrait} has one.
     * <p>
     * This is what makes every appearance trait apply to the disguise rather than to the entity underneath: the traits all
     * read {@code getCosmeticEntity()}, while movement and navigation keep working on the real one.
     */
    @Override
    public Entity getCosmeticEntity() {
        DisguiseTrait trait = getTraitNullable(DisguiseTrait.class);
        Entity disguise = trait == null ? null : trait.getCosmeticEntity();
        return disguise == null ? getEntity() : disguise;
    }

    @Override
    public EntityType<?> getCosmeticEntityType() {
        DisguiseTrait trait = getTraitNullable(DisguiseTrait.class);
        return trait == null || trait.getDisguiseType() == null ? super.getCosmeticEntityType()
                : trait.getDisguiseType();
    }

    private void discardFailedSpawn() {
        // Native insertion may already have paired viewers before the spawn event rejects the NPC.
        ScoreboardTrait scoreboard = getTraitNullable(ScoreboardTrait.class);
        if (scoreboard != null) {
            scoreboard.onDespawn(DespawnReason.PENDING_RESPAWN);
        }
        entityController.remove();
    }

    /** Refreshes attached scoreboard traits, including teams not created during pairing. */
    private void updateScoreboard() {
        ScoreboardTrait trait = getTraitNullable(ScoreboardTrait.class);
        if (trait != null) {
            trait.update();
        }
    }

    /**
     * Adds the {@code HOLOGRAM_RENDERER} guard on top of the base check.
     * <p>
     * Hologram lines are NPCs themselves, and an armour-stand line whose text contains a colour code would otherwise
     * satisfy the base condition and be given a hologram of its own — recursively, forever. Carrying the renderer in the
     * line's metadata is what tells this apart from a real NPC.
     */
    @Override
    public boolean requiresNameHologram() {
        return !data().has(NPC.Metadata.HOLOGRAM_RENDERER)
                && (super.requiresNameHologram() || Setting.ALWAYS_USE_NAME_HOLOGRAM.asBoolean());
    }

    @Override
    protected void setNameInternal(String name) {
        super.setNameInternal(name);
        if (requiresNameHologram()) {
            HologramRenderer renderer = getOrAddTrait(HologramTrait.class).getNameRenderer();
            if (renderer != null) {
                renderer.updateText(this, getRawName());
            }
        }
        updateCustomName();
    }

    private void updateCustomName() {
        Entity entity = getEntity();
        if (entity == null)
            return;
        if (entity instanceof Display.TextDisplay display) {
            // A rendered hologram component or explicitly authored text takes priority over the fallback NPC name.
            Object component = data().get(NPC.Metadata.TEXT_DISPLAY_COMPONENT);
            var textTrait = getCosmeticEntity() == entity
                    ? getTraitNullable(net.citizensnpcs.trait.versioned.TextDisplayTrait.class) : null;
            display.setText(component instanceof net.minecraft.network.chat.Component text ? text
                    : textTrait != null && textTrait.getText() != null
                            ? net.citizensnpcs.api.util.TextParser.parse(textTrait.getText())
                            : Messaging.minecraftComponentFromRawMessage(getFullName()));
            return;
        }
        if (entity instanceof net.minecraft.world.entity.Interaction) {
            entity.setCustomName(null);
            return;
        }
        entity.setCustomName(coloredNameComponentCache instanceof net.minecraft.network.chat.Component
                ? (net.minecraft.network.chat.Component) coloredNameComponentCache
                : Messaging.minecraftComponentFromRawMessage(getFullName()));
    }

    private void updateCustomNameVisibility() {
        Entity entity = getEntity();
        if (entity == null)
            return;
        String visible = data().<Object> get(NPC.Metadata.NAMEPLATE_VISIBLE, true).toString();
        if (visible.equals("true") || visible.equals("hover")) {
            updateCustomName();
        }
        entity.setCustomNameVisible(Boolean.parseBoolean(visible));
    }

    @Override
    public void speak(SpeechContext context) {
        NPCSpeechEvent event = new NPCSpeechEvent(this, context);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled())
            return;

        // chat to the world with the no-targets format
        if (!context.hasRecipients()) {
            talkToBystanders(this, Setting.CHAT_FORMAT.asString().replace("<text>", context.getMessage()), context);
            return;
        }
        String text = Setting.CHAT_FORMAT_TO_TARGET.asString().replace("<text>", context.getMessage());
        if (context.size() <= 1) {
            String targetName = "";
            for (Talkable talkable : context) {
                talkable.talkTo(context, text);
                targetName = talkable.getName();
            }
            if (!Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT.asBoolean())
                return;
            String bystanderText = Setting.CHAT_FORMAT_TO_BYSTANDERS.asString().replace("<target>", targetName).replace("<text>",
                    context.getMessage());
            talkToBystanders(this, bystanderText, context);
            return;
        }
        List<String> targetNames = new ArrayList<>();
        for (Talkable talkable : context) {
            talkable.talkTo(context, text);
            targetNames.add(talkable.getName());
        }
        if (!Setting.CHAT_BYSTANDERS_HEAR_TARGETED_CHAT.asBoolean())
            return;
        String bystanderText = Setting.CHAT_FORMAT_WITH_TARGETS_TO_BYSTANDERS.asString()
                .replace("<targets>", joinTargetNames(targetNames)).replace("<text>", context.getMessage());
        talkToBystanders(this, bystanderText, context);
    }

    /**
     * Renders a target list using the four-part {@code multiple-targets-format} — first, separator, last, overflow.
     * Ported verbatim from upstream, including its quirk of substituting {@code <npc>} rather than {@code <target>} in
     * the middle and last parts.
     */
    private static String joinTargetNames(List<String> targetNames) {
        String[] format = Setting.CHAT_MULTIPLE_TARGETS_FORMAT.asString().split("\\|");
        if (format.length != 4) {
            Messaging.severe("npc.chat.options.multiple-targets-format invalid!");
            return String.join(", ", targetNames);
        }
        int max = Setting.CHAT_MAX_NUMBER_OF_TARGETS.asInt();
        if (max == 1)
            return format[0].replace("<target>", targetNames.get(0)) + format[3];

        if (max == 2 || targetNames.size() == 2) {
            if (targetNames.size() == 2)
                return format[0].replace("<target>", targetNames.get(0))
                        + format[2].replace("<target>", targetNames.get(1));
            return format[0].replace("<target>", targetNames.get(0))
                    + format[1].replace("<target>", targetNames.get(1)) + format[3];
        }
        StringBuilder targets = new StringBuilder(format[0].replace("<target>", targetNames.get(0)));
        int x = 1;
        for (x = 1; x < max - 1; x++) {
            if (targetNames.size() - 1 == x) {
                break;
            }
            targets.append(format[1].replace("<npc>", targetNames.get(x)));
        }
        targets.append(targetNames.size() == max ? format[2].replace("<npc>", targetNames.get(x)) : format[3]);
        return targets.toString();
    }

    /**
     * Delivers the message to everyone in chat range who is not already an explicit recipient.
     * <p>
     * Upstream calls Bukkit's {@code getNearbyEntities}; the equivalent is a level query over an inflated bounding box.
     */
    private static void talkToBystanders(NPC npc, String text, SpeechContext context) {
        Entity source = npc.getEntity();
        if (source == null || !(source.level() instanceof ServerLevel))
            return;
        double range = Setting.CHAT_RANGE.asDouble();
        List<Entity> bystanders = source.level().getEntities(source, source.getBoundingBox().inflate(range));
        for (Entity bystander : bystanders) {
            boolean shouldTalk = true;
            if (!Setting.TALK_CLOSE_TO_NPCS.asBoolean() && CitizensAPI.getNPCRegistry().isNPC(bystander)) {
                shouldTalk = false;
            }
            if (context.hasRecipients()) {
                for (Talkable target : context) {
                    if (target.getEntity() != null && target.getEntity().equals(bystander)) {
                        shouldTalk = false;
                        break;
                    }
                }
            }
            if (shouldTalk) {
                new TalkableEntity(bystander).talkNear(context, text);
            }
        }
    }

    @Override
    public String toString() {
        EntityType<?> mobType = hasTrait(MobType.class) ? getTraitNullable(MobType.class).getType() : null;
        return getId() + "{" + getRawName() + ", " + (mobType == null ? "?" : EntityType.getKey(mobType)) + "}";
    }

    /** The metadata key {@code NPCSelector} stores selections under. Kept as a literal so the two stay in step. */
    private static final String SELECTORS_METADATA = "selectors";
}
