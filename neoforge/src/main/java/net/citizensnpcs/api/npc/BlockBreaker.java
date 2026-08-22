package net.citizensnpcs.api.npc;

import java.util.function.BiConsumer;

import net.citizensnpcs.api.ai.tree.Behavior;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A {@link Runnable} task that will break a block over time just as a normal player would. Should be run every tick
 * until completed.
 * <p>
 * This class also implements the {@link Behavior} interface for ease of use.
 * <p>
 * The actual implementation lives in the mod rather than the API.
 */
public abstract class BlockBreaker implements Behavior {
    /**
     * Identifies the block to break. Bukkit's {@code Block} carries its own world reference, so upstream passes a
     * single object; Minecraft's {@link BlockPos} does not, so the level travels alongside it.
     */
    public record BlockTarget(Level level, BlockPos pos) {
    }

    public static class BlockBreakerConfiguration {
        private BiConsumer<BlockTarget, ItemStack> blockBreaker = (target,
                item) -> target.level().destroyBlock(target.pos(), true);
        private Runnable callback;
        private ItemStack itemStack;
        private float modifier = 1;
        private double radius = 0;

        public BiConsumer<BlockTarget, ItemStack> blockBreaker() {
            return blockBreaker;
        }

        /**
         * @param breaker
         *            The function that actually breaks the block. By default, this drops the block's normal loot via
         *            {@link Level#destroyBlock(BlockPos, boolean)}.
         */
        public void blockBreaker(BiConsumer<BlockTarget, ItemStack> breaker) {
            blockBreaker = breaker;
        }

        public float blockStrengthModifier() {
            return modifier;
        }

        /**
         * @param modifier
         *            The block strength modifier
         */
        public BlockBreakerConfiguration blockStrengthModifier(float modifier) {
            this.modifier = modifier;
            return this;
        }

        public Runnable callback() {
            return callback;
        }

        /**
         * @param callback
         *            A callback that is run on completion
         */
        public BlockBreakerConfiguration callback(Runnable callback) {
            this.callback = callback;
            return this;
        }

        public ItemStack item() {
            return itemStack;
        }

        /**
         *
         * @param stack
         *            The item to simulate the NPC using to break the block (e.g. an axe for wood)
         */
        public BlockBreakerConfiguration item(ItemStack stack) {
            itemStack = stack;
            return this;
        }

        public double radius() {
            return radius;
        }

        /**
         * @param radius
         *            The maximum radius to be from the target block. The NPC will attempt to pathfind towards the
         *            target block if this is specified and it is outside of the radius.
         */
        public BlockBreakerConfiguration radius(double radius) {
            this.radius = radius;
            return this;
        }
    }

    public static final BlockBreakerConfiguration EMPTY_CONFIG = new BlockBreakerConfiguration();
}
