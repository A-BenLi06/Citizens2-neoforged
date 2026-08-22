package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.monster.Zombie;

/**
 * Persists the baby/adult age of an NPC. Negative ages are babies, and the value counts up towards adulthood.
 * <p>
 * <b>The age lock is implemented here rather than on the entity.</b> Bukkit has {@code Ageable#setAgeLock}, backed by a
 * CraftBukkit-only flag; vanilla has no such thing and {@code AgeableMob.aiStep} increments the age every tick. So while
 * locked the stored age is re-asserted each tick, and while unlocked the entity's age is read back instead. The visible
 * behaviour matches upstream either way.
 * <p>
 * Zombies are not {@link AgeableMob} in vanilla but do have a baby flag, so they get the sign of the age.
 */
@TraitName("age")
public class Age extends Trait {
    @Persist
    private int age = 0;
    @Persist
    private boolean locked = true;

    public Age() {
        super("age");
    }

    /**
     * Send a brief description of the current state of the trait to the supplied sender.
     */
    public void describe(CommandSourceStack sender) {
        Messaging.sendTr(sender, Messages.AGE_TRAIT_DESCRIPTION, npc.getName(), age, locked);
    }

    public int getAge() {
        return age;
    }

    public boolean isLocked() {
        return locked;
    }

    @Override
    public void onSpawn() {
        applyAge();
    }

    private void applyAge() {
        if (npc.getEntity() instanceof AgeableMob ageable) {
            ageable.setAge(age);
        } else if (npc.getEntity() instanceof Zombie zombie) {
            zombie.setBaby(age < 0);
        }
    }

    @Override
    public void run() {
        if (npc.getEntity() instanceof AgeableMob ageable) {
            if (locked) {
                // vanilla ages the mob every tick; holding the value here is what the lock means
                if (ageable.getAge() != age) {
                    ageable.setAge(age);
                }
            } else {
                age = ageable.getAge();
            }
        }
    }

    public void setAge(int age) {
        this.age = age;
        applyAge();
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /**
     * Toggles the age lock and returns whether the age is now locked.
     */
    public boolean toggle() {
        locked = !locked;
        return locked;
    }

    @Override
    public String toString() {
        return "Age{age=" + age + ",locked=" + locked + "}";
    }
}
