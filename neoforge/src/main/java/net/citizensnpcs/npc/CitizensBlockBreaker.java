package net.citizensnpcs.npc;

import net.citizensnpcs.api.ai.tree.BehaviorStatus;
import net.citizensnpcs.api.npc.BlockBreaker;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.util.PlayerAnimation;
import net.citizensnpcs.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks a block over time the way a player would: the same strength formula, the same cracking animation, the same
 * dependence on the tool held, on the mining effects, and on whether the digger is standing on the ground.
 * <p>
 * Upstream splits this in two — a platform-independent half in {@code AbstractBlockBreaker} and an NMS half per Minecraft
 * version. There is only one platform here, so it is one class.
 */
public class CitizensBlockBreaker extends BlockBreaker {
    private final BlockBreakerConfiguration configuration;
    private int currentDamage;
    private final Entity entity;
    private boolean isDigging = true;
    private final ServerLevel level;
    private final NPC npc;
    private final BlockPos pos;
    private boolean setTarget;
    private int startDigTick;

    public CitizensBlockBreaker(NPC npc, Entity entity, ServerLevel level, BlockPos pos,
            BlockBreakerConfiguration config) {
        this.npc = npc;
        this.entity = entity;
        this.level = level;
        this.pos = pos.immutable();
        this.startDigTick = currentTick();
        this.configuration = config;
    }

    private void cancelNavigation() {
        if (setTarget && npc != null && npc.getNavigator().isNavigating()) {
            npc.getNavigator().cancelNavigation();
        }
        setTarget = false;
    }

    /** Milliseconds rather than the server tick counter, so a lagging server does not dig faster. */
    private static int currentTick() {
        return (int) (System.currentTimeMillis() / 50);
    }

    private float getDamage(int tickDifference) {
        return getStrength(level.getBlockState(pos)) * (tickDifference + 1) * configuration.blockStrengthModifier();
    }

    private ItemStack getItemStack() {
        if (configuration.item() != null)
            return configuration.item();
        return entity instanceof LivingEntity living ? living.getMainHandItem() : ItemStack.EMPTY;
    }

    /** How much of the block breaks per tick, as vanilla computes it for a player. */
    private float getStrength(BlockState state) {
        float base = state.getDestroySpeed(level, pos);
        if (base < 0)
            return 0;
        return !isDestroyable(state) ? 1.0F / base / 100.0F : strengthMod(state) / base / 30.0F;
    }

    private boolean inRange() {
        Location centre = Util.getCenterLocation(level, pos);
        double xz = Math.sqrt(Math.pow(centre.getX() - entity.getX(), 2) + Math.pow(centre.getZ() - entity.getZ(), 2));
        return xz <= configuration.radius() && Math.abs(centre.getY() - entity.getY()) <= 3;
    }

    private boolean isDestroyable(BlockState state) {
        if (!state.requiresCorrectToolForDrops())
            return true;
        ItemStack current = getItemStack();
        return current != null && current.isCorrectToolForDrops(state);
    }

    @Override
    public void reset() {
        cancelNavigation();
        if (configuration.callback() != null) {
            configuration.callback().run();
        }
        isDigging = false;
        setBlockDamage(currentDamage = -1);
    }

    @Override
    public BehaviorStatus run() {
        if (entity == null || entity.isRemoved())
            return BehaviorStatus.FAILURE;
        if (!isDigging)
            return BehaviorStatus.SUCCESS;

        int currentTick = currentTick();
        if (configuration.radius() > 0) {
            if (!inRange()) {
                // out of reach: walk closer, and do not count the time spent walking as progress
                startDigTick = currentTick;
                if (npc != null && !npc.getNavigator().isNavigating()) {
                    npc.getNavigator().setTarget(Location.fromBlockPos(level, pos));
                    npc.getNavigator().getLocalParameters().pathDistanceMargin(
                            Math.max(1, npc.getNavigator().getLocalParameters().pathDistanceMargin()));
                    npc.getNavigator().getLocalParameters().distanceMargin(Math.max(configuration.radius() - 1, 0.75));
                    setTarget = true;
                }
                return BehaviorStatus.RUNNING;
            }
            if (setTarget) {
                cancelNavigation();
            }
        }
        if (npc != null) {
            npc.faceLocation(Util.getCenterLocation(level, pos));
        }
        if (entity instanceof ServerPlayer player && currentTick % 5 == 0) {
            PlayerAnimation.ARM_SWING.play(player);
        }
        if (level.getBlockState(pos).isAir())
            return BehaviorStatus.SUCCESS;

        float damage = getDamage(currentTick - startDigTick);
        if (damage >= 1F) {
            configuration.blockBreaker().accept(new BlockTarget(level, pos), getItemStack());
            return BehaviorStatus.SUCCESS;
        }
        int modifiedDamage = (int) (damage * 10.0F);
        if (modifiedDamage != currentDamage) {
            setBlockDamage(modifiedDamage);
            currentDamage = modifiedDamage;
        }
        return BehaviorStatus.RUNNING;
    }

    /** Drives the cracking overlay every viewer sees. -1 clears it. */
    private void setBlockDamage(int modifiedDamage) {
        level.destroyBlockProgress(entity == null ? 0 : entity.getId(), pos, modifiedDamage);
    }

    @Override
    public boolean shouldExecute() {
        return !level.getBlockState(pos).isAir();
    }

    /** The tool's speed against this block, adjusted for enchantments, potion effects and footing, as vanilla does. */
    private float strengthMod(BlockState state) {
        ItemStack item = getItemStack();
        float speed = item == null ? 1 : item.getDestroySpeed(state);
        if (entity instanceof LivingEntity living) {
            // Vanilla registers MINING_EFFICIENCY, BLOCK_BREAK_SPEED and SUBMERGED_MINING_SPEED on Player only, and its
            // own mining-speed code lives on Player, so it can read them unconditionally. An NPC is whatever entity type
            // it was created as, and asking a pig for an attribute it does not have throws - which used to abort
            // update() every tick for as long as the breaker ran. Each read is guarded rather than gated on
            // "is a player", so an attribute another mod adds to some other entity is still honoured.
            if (speed > 1.0F && living.getAttributes().hasAttribute(Attributes.MINING_EFFICIENCY)) {
                speed += (float) living.getAttributeValue(Attributes.MINING_EFFICIENCY);
            }
            if (MobEffectUtil.hasDigSpeed(living)) {
                speed *= 1.0F + (MobEffectUtil.getDigSpeedAmplification(living) + 1) * 0.2F;
            }
            MobEffectInstance fatigue = living.getEffect(MobEffects.DIG_SLOWDOWN);
            if (fatigue != null) {
                speed *= switch (fatigue.getAmplifier()) {
                    case 0 -> 0.3F;
                    case 1 -> 0.09F;
                    case 2 -> 0.0027F;
                    default -> 8.1E-4F;
                };
            }
            if (living.getAttributes().hasAttribute(Attributes.BLOCK_BREAK_SPEED)) {
                speed *= (float) living.getAttributeValue(Attributes.BLOCK_BREAK_SPEED);
            }
            if (living.isEyeInFluid(FluidTags.WATER)
                    && living.getAttributes().hasAttribute(Attributes.SUBMERGED_MINING_SPEED)) {
                speed *= (float) living.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
            }
        }
        if (entity != null && !entity.onGround()) {
            speed /= 5.0F;
        }
        return speed;
    }
}
