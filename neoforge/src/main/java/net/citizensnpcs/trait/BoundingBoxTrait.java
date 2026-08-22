package net.citizensnpcs.trait;

import java.util.function.Function;
import java.util.function.Supplier;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.BoundingBox;
import net.citizensnpcs.api.util.EntityDim;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Resizes an NPC's hitbox, and gives it a clickable box of the same size.
 * <p>
 * Two things are needed for a resized NPC to behave: the entity's own dimensions, which decide what it collides with,
 * and an {@link Interaction} entity riding along, which is what players actually click — a display entity has no hitbox
 * of its own, and a resized mob still reports its vanilla size to the client.
 */
@TraitName("boundingbox")
public class BoundingBoxTrait extends Trait implements Supplier<BoundingBox> {
    private EntityDim base;
    private Function<EntityDim, BoundingBox> function;
    @Persist
    private float height = -1;
    private NPC interaction;
    @Persist
    private Vec3 offset = Vec3.ZERO;
    @Persist
    private float scale = -1;
    @Persist
    private float width = -1;

    public BoundingBoxTrait() {
        super("boundingbox");
    }

    @Override
    public BoundingBox get() {
        Entity entity = npc.getEntity();
        if (entity == null)
            return null;
        if (function != null) {
            BoundingBox box = function.apply(getAdjustedDimensions());
            apply(entity, box.toDimensions());
            return box.add(Location.fromEntity(entity));
        }
        EntityDim dim = getAdjustedDimensions();
        apply(entity, dim);
        return new BoundingBox(entity.getX() - dim.width / 2 + offset.x, entity.getY() + offset.y,
                entity.getZ() - dim.width / 2 + offset.z, entity.getX() + dim.width / 2 + offset.x,
                entity.getY() + dim.height + offset.y, entity.getZ() + dim.width / 2 + offset.z);
    }

    /** Writes the size onto the entity. Both halves are needed: the box is recomputed from the dimensions on every move. */
    private void apply(Entity entity, EntityDim dim) {
        EntityDimensions current = entity.dimensions;
        if (current.width() == dim.width && current.height() == dim.height)
            return;
        entity.dimensions = EntityDimensions.scalable(dim.width, dim.height).withEyeHeight(dim.height * 0.85f);
        entity.setBoundingBox(entity.dimensions.makeBoundingBox(entity.position().add(offset)));
    }

    public EntityDim getAdjustedDimensions() {
        EntityDim desired = base == null ? new EntityDim(1, 1) : base;
        if (scale != -1) {
            desired = desired.mul(scale);
        }
        return new EntityDim(width == -1 ? desired.width : width, height == -1 ? desired.height : height);
    }

    /** Display entities are anchored differently from mobs, so the clickable box has to be shifted onto them. */
    private Location getBaseLocation(Entity entity) {
        Location loc = Location.fromEntity(entity);
        if (entity.getType() == EntityType.ITEM_DISPLAY)
            return loc.add(0, -0.5, 0);
        if (entity.getType() == EntityType.BLOCK_DISPLAY)
            return loc.add(0.5, 0, 0.5);
        return loc;
    }

    @Override
    public void onDespawn() {
        npc.data().remove(NPC.Metadata.BOUNDING_BOX_FUNCTION);
        if (interaction != null) {
            interaction.destroy();
            interaction = null;
        }
    }

    @Override
    public void onRemove() {
        onDespawn();
    }

    @Override
    public void onSpawn() {
        Entity entity = npc.getEntity();
        if (entity == null)
            return;
        if (entity.getType() == EntityType.BLOCK_DISPLAY) {
            base = EntityDim.from(new BoundingBox(0, 0, 0, 1, 1, 1));
        } else if (entity instanceof FallingBlockEntity falling) {
            VoxelShape shape = falling.getBlockState().getCollisionShape(entity.level(), entity.blockPosition());
            base = shape.isEmpty() ? new EntityDim(1, 1)
                    : EntityDim.from(BoundingBox.convert(shape.bounds()));
        } else if (entity.getType() == EntityType.ITEM_DISPLAY) {
            base = EntityDim.from(new BoundingBox(0, 0, 0, 1, 1, 1));
        } else {
            base = new EntityDim(entity.getBbWidth(), entity.getBbHeight());
        }
        npc.data().set(NPC.Metadata.BOUNDING_BOX_FUNCTION, this);

        interaction = CitizensAPI.getTemporaryNPCRegistry().createNPC(EntityType.INTERACTION, "");
        interaction.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
        interaction.addTrait(new ClickRedirectTrait(npc));
        interaction.spawn(getBaseLocation(entity));
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        // reapplies the size: anything that respawns or repossesses the entity resets it to the type's default
        get();
        if (interaction == null)
            return;
        if (!interaction.isSpawned()) {
            interaction.spawn(getBaseLocation(npc.getEntity()));
            return;
        }
        EntityDim dim = getAdjustedDimensions();
        interaction.teleport(getBaseLocation(npc.getEntity()), TeleportCause.PLUGIN);
        if (interaction.getEntity() instanceof Interaction box) {
            box.setWidth(dim.width);
            box.setHeight(dim.height);
            box.setResponse(true);
        }
    }

    public void setBoundingBoxFunction(Function<EntityDim, BoundingBox> func) {
        function = func;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public void setOffset(Vec3 offset) {
        this.offset = offset == null ? Vec3.ZERO : offset;
    }

    public void setScale(float scale) {
        this.scale = scale;
    }

    public void setWidth(float width) {
        this.width = width;
    }
}
