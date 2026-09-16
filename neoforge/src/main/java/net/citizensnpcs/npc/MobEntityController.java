package net.citizensnpcs.npc;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.npc.ai.NPCHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.OminousItemSpawner;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The single {@link EntityController} for every mob type.
 * <p>
 * This is the largest structural saving of the port. Upstream needs one controller subclass plus one
 * {@code EntityXxxNPC} subclass plus one {@code CraftXxxNPC} wrapper per mob — around 140 classes — because a Bukkit
 * NPC has to (a) register a custom entity type so the client is told what to render, and (b) present a
 * {@code CraftEntity} that implements {@code NPCHolder}. On NeoForge neither applies: the vanilla
 * {@link EntityType} is used as-is, so a vanilla client sees an ordinary mob, and there is no wrapper layer to
 * subclass. One controller creating a vanilla entity and attaching the NPC to it covers every type.
 * <p>
 * Because the entity is a plain vanilla instance rather than a Citizens subclass, the per-entity behaviour upstream
 * gets by overriding methods ({@code checkDespawn}, {@code isPushable}, {@code save}, sound suppression, fall damage,
 * knockback) is instead applied here at creation time and, where it has to be continuous, from the tick hook in
 * {@code Citizens}. The cases that genuinely need a method override — chiefly the fake player — keep a bespoke
 * controller; see {@code HumanController}.
 */
public class MobEntityController extends AbstractEntityController {
    private final EntityType<?> type;

    public MobEntityController(EntityType<?> type) {
        this.type = type;
    }

    @Override
    protected Entity createEntity(Location at, NPC npc) {
        ServerLevel level = at.getWorld();
        if (level == null)
            throw new IllegalStateException("cannot create an NPC entity in an unloaded level");

        Entity entity = type.create(level);
        if (entity == null) {
            // EntityType.PLAYER has no factory - a player entity needs a connection and a game profile, which is
            // what HumanController builds. Everything else returning null means the type is not spawnable at all.
            if (type == EntityType.PLAYER)
                throw new IllegalStateException(
                        "player-type NPCs need HumanController, which is not ported yet (P4b)");
            throw new IllegalStateException("entity type " + EntityType.getKey(type) + " cannot be spawned");
        }

        entity.moveTo(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        entity.setYHeadRot(at.getYaw());

        if (npc != null) {
            entity.setUUID(npc.getUniqueId());
            NPCRegistries.link(entity, npc);
            net.citizensnpcs.api.util.EntityUtil.recordLivingType(entity);
            applyItem(entity, npc);
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                // Citizens drives movement itself, so vanilla AI is cleared unless the NPC opts back into it
                if (!npc.useMinecraftAI()) {
                    clearGoals(mob);
                }
                mob.setPersistenceRequired();
                mob.setCanPickUpLoot(npc.data().get(NPC.Metadata.PICKUP_ITEMS, false));
            }
            if (entity instanceof ItemEntity item) {
                item.setNeverPickUp();
                item.setUnlimitedLifetime();
            }
        }
        return entity;
    }

    /**
     * Gives the entity the item the NPC was created with, for the types that show one.
     * <p>
     * Upstream does this in a controller subclass per entity type — {@code ItemDisplayController},
     * {@code BlockDisplayController}, {@code ItemController} and so on. Collapsing those into one generic controller
     * left the item with nowhere to be applied, so the dispatch lives here instead. Every setter involved is one vanilla
     * keeps private because it only ever reads the value from NBT.
     * <p>
     * Block-shaped entities need a block rather than an item, so an item that is not a block form is ignored rather than
     * turned into air.
     */
    public static void applyItem(Entity entity, NPC npc) {
        if (!npc.data().has(NPC.Metadata.ITEM_ID))
            return;
        ItemStack stack = npc.getItemProvider().get();
        if (stack == null || stack.isEmpty())
            return;
        if (entity instanceof Display.ItemDisplay display) {
            display.setItemStack(stack.copy());
        } else if (entity instanceof ItemEntity item) {
            item.setItem(stack.copy());
        } else if (entity instanceof ItemFrame frame) {
            // covers the glow item frame too, which is a subclass
            frame.setItem(stack.copy());
        } else if (entity instanceof OminousItemSpawner spawner) {
            spawner.setItem(stack.copy());
        } else if (entity instanceof Display.BlockDisplay display) {
            BlockState state = blockStateOf(stack);
            if (state != null) {
                display.setBlockState(state);
            }
        } else if (entity instanceof FallingBlockEntity falling) {
            BlockState state = blockStateOf(stack);
            if (state != null) {
                falling.blockState = state;
            }
        }
    }

    /**
     * @return the default state of the block this item places, or null when the item is not a block
     */
    private static BlockState blockStateOf(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block ? block.getBlock().defaultBlockState() : null;
    }

    /**
     * Strips vanilla AI.
     * <p>
     * Upstream reaches the two selectors reflectively because they are private in CraftBukkit's remap; in NeoForge both
     * are public fields with a {@code removeAllGoals} helper.
     * <p>
     * The brain has to be emptied as well. A large family of mobs — villagers, piglins, hoglins, axolotls, frogs, goats,
     * camels, allays, sniffers, wardens, breezes — runs entirely on {@link net.minecraft.world.entity.ai.Brain}
     * behaviours and never touches the goal selectors, so clearing only those left their AI fully active. Symptoms
     * ranged from the NPC wandering off to trait state being overwritten: a villager NPC would drop the profession
     * {@code VillagerProfession} had just set, because a brain behaviour clears it when no job site can be found.
     * <p>
     * The look control is swapped rather than cleared. Vanilla's {@link LookControl#tick()} starts by zeroing the pitch,
     * and it runs from {@code Mob.serverAiStep} — which neither the goal selectors nor the brain gate. An NPC could
     * therefore never hold a pitch: {@code RotationTrait} would set one and the next tick would flatten it. Upstream
     * never meets this because its entity subclasses override {@code serverAiStep} and tick the controls themselves.
     */
    public static void clearGoals(Mob mob) {
        mob.goalSelector.removeAllGoals(goal -> true);
        mob.targetSelector.removeAllGoals(goal -> true);
        mob.getBrain().removeAllBehaviors();
        mob.lookControl = new NPCLookControl(mob);
    }

    /** A look control that leaves the pitch alone; everything else is vanilla's. */
    private static class NPCLookControl extends LookControl {
        NPCLookControl(Mob mob) {
            super(mob);
        }

        @Override
        protected boolean resetXRotOnTick() {
            return false;
        }
    }

    /**
     * @return an {@link NPCHolder} view of the entity, or null if it is not a Citizens NPC
     */
    public static NPC getNPC(Entity entity) {
        return NPCRegistries.lookup(entity);
    }
}
