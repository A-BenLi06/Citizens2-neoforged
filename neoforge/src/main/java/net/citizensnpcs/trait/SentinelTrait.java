package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Native melee guard behavior reading Sentinel's persisted fields: targeting, pursuit, retaliation, squad aggro,
 * respawn, health, regeneration, and greetings. Ranged combat and other unimplemented Sentinel features remain tracked
 * in the parity audit. Historical projectile counters alone do not establish whether those features are required.
 * <p>
 * Melee movement and attacks use the Citizens navigator, with Sentinel's reach and attackRate mapped to its parameters.
 */
@TraitName("sentinel")
public class SentinelTrait extends Trait {
    // --- combat shape, all under Sentinel's own key names ---
    @Persist
    private double range = 20;
    @Persist("chaseRange")
    private double chaseRange = 70;
    @Persist("attackRate")
    private int attackRate = 30;
    @Persist("healRate")
    private int healRate = 100;
    @Persist
    private double reach = 4.5;
    @Persist
    private double health = 20;
    @Persist
    private double speed = 1;
    @Persist
    private boolean fightback = true;
    @Persist("close_chase")
    private boolean closeChase = true;
    @Persist
    private boolean invincible;
    @Persist("respawnTime")
    private int respawnTime = 100;
    @Persist("enemyTargetTime")
    private int enemyTargetTime = 6000;
    @Persist("retain_target")
    private boolean retainTarget;
    @Persist("allow_knockback")
    private boolean allowKnockback = true;
    @Persist
    private String squad;
    @Persist("spawnPoint")
    private Location spawnPoint;
    // --- greetings ---
    @Persist("greeting_text")
    private String greetingText = "";
    @Persist("warning_text")
    private String warningText = "";
    @Persist("greet_range")
    private double greetRange = 10;
    @Persist("greet_rate")
    private int greetRate = 100;

    private Entity target;
    private int targetTicks;
    private int greetCooldown;
    private final Map<java.util.UUID, Integer> greeted = new HashMap<>();
    private int respawnCountdown = -1;
    private long timeSinceHeal;
    /** Compiled once per distinct pattern rather than per player per tick. */
    private final List<Pattern> heldItemPatterns = new ArrayList<>();
    private final Set<String> heldItemRaw = new LinkedHashSet<>();
    private final Set<String> groups = new LinkedHashSet<>();
    /** Everything under the sentinel key that this trait does not model, kept so a save round-trips losslessly. */
    private final Map<String, Object> passthrough = new HashMap<>();

    public SentinelTrait() {
        super("sentinel");
    }

    /** @return the squad name, or null; guards in one squad share aggro */
    public String getSquad() {
        return squad;
    }

    public Entity getTarget() {
        return target;
    }

    /**
     * Sentinel stores its target rules under {@code allTargets}, and its stats and unmodelled options as flat keys. Both
     * are read here rather than through {@link Persist} so that anything this trait does not understand survives a
     * save/load cycle instead of being dropped.
     */
    @Override
    public void load(DataKey key) {
        heldItemRaw.clear();
        groups.clear();
        passthrough.clear();
        for (DataKey sub : key.getRelative("allTargets.byHeldItem").getIntegerSubKeys()) {
            heldItemRaw.add(sub.getString(""));
        }
        for (DataKey sub : key.getRelative("allTargets.byGroup").getIntegerSubKeys()) {
            groups.add(sub.getString(""));
        }
        compilePatterns();
        for (DataKey sub : key.getSubKeys()) {
            if (sub.name().startsWith("stats_")) {
                passthrough.put(sub.name(), sub.getRaw(""));
            }
        }
        if (spawnPoint == null && npc != null) {
            spawnPoint = npc.getStoredLocation();
        }
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("allTargets.byHeldItem");
        int i = 0;
        for (String pattern : heldItemRaw) {
            key.setString("allTargets.byHeldItem." + i++, pattern);
        }
        key.removeKey("allTargets.byGroup");
        i = 0;
        for (String group : groups) {
            key.setString("allTargets.byGroup." + i++, group);
        }
        for (Map.Entry<String, Object> entry : passthrough.entrySet()) {
            key.setRaw(entry.getKey(), entry.getValue());
        }
    }

    private void compilePatterns() {
        heldItemPatterns.clear();
        for (String raw : heldItemRaw) {
            try {
                // Sentinel matches its held-item rules as regexes against the item name
                heldItemPatterns.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException ex) {
                Messaging.severe("Sentinel target pattern", raw, "on NPC", npc == null ? -1 : npc.getId(),
                        "is not a valid regex and will never match:", ex.getMessage());
            }
        }
    }

    @Override
    public void onSpawn() {
        applyCombatShape();
        if (spawnPoint == null) {
            spawnPoint = npc.getStoredLocation();
        }
        respawnCountdown = -1;
    }

    /** Pushes Sentinel's numbers onto the things that already implement them. */
    private void applyCombatShape() {
        npc.getNavigator().getDefaultParameters().attackRange(reach).attackDelayTicks(attackRate)
                .speedModifier((float) speed);
        // -1 means "leave it to the entity", which is Sentinel's convention for damage and armour too
        if (health > 0 && npc.getEntity() instanceof LivingEntity) {
            ScaledMaxHealthTrait scaled = npc.getOrAddTrait(ScaledMaxHealthTrait.class);
            scaled.setMaxHealth(health);
            // Adding the trait to an already spawned NPC called onSpawn before its value was assigned.
            scaled.onSpawn();
        }
        npc.data().setPersistent(NPC.Metadata.KNOCKBACK, allowKnockback);
        // Sentinel sets protection in both directions, including the default non-invincible guard.
        npc.data().setPersistent(NPC.Metadata.DEFAULT_PROTECTED, invincible);
        if (npc.getEntity() != null)
            npc.getEntity().setInvulnerable(invincible);
    }

    /**
     * Called when something damages this NPC. With {@code fightback} on, the attacker becomes the target — which is the
     * only way an unarmed guard with no target rules ever fights at all.
     */
    public void onDamaged(Entity damager) {
        if (!fightback || damager == null || damager == npc.getEntity())
            return;
        if (target == null || !retainTarget) {
            acquire(damager);
        }
    }

    @Override
    public void run() {
        if (!npc.isSpawned()) {
            tickRespawn();
            return;
        }
        tickHealing();
        if (target != null && !isStillValid(target)) {
            clearTarget();
        }
        if (target == null) {
            acquire(findEnemy());
        }
        if (target != null) {
            targetTicks++;
            if (targetTicks > enemyTargetTime || outOfChaseRange()) {
                clearTarget();
            }
        }
        greet();
    }

    private void tickHealing() {
        timeSinceHeal++;
        if (healRate <= 0 || timeSinceHeal <= healRate
                || !(npc.getEntity() instanceof LivingEntity living) || !living.isAlive())
            return;
        if (living.getHealth() >= living.getMaxHealth())
            return;
        // The old plugin restores one nominal hit point, not one heart. Respect scaled health above vanilla's cap.
        float amount = health > living.getMaxHealth() ? (float) (living.getMaxHealth() / health) : 1;
        living.setHealth(Math.min(living.getMaxHealth(), living.getHealth() + amount));
        timeSinceHeal = 0;
    }

    private void tickRespawn() {
        if (respawnTime < 0 || spawnPoint == null)
            return;
        if (respawnCountdown < 0) {
            respawnCountdown = respawnTime;
            return;
        }
        if (--respawnCountdown <= 0) {
            respawnCountdown = -1;
            npc.spawn(spawnPoint, SpawnReason.RESPAWN);
        }
    }

    private boolean isStillValid(Entity candidate) {
        if (candidate.isRemoved())
            return false;
        return !(candidate instanceof LivingEntity living) || living.isAlive();
    }

    private boolean outOfChaseRange() {
        Entity self = npc.getEntity();
        if (self == null || target == null)
            return true;
        if (target.level() != self.level())
            return true;
        Location from = spawnPoint != null && !closeChase ? spawnPoint : Location.of(self);
        double limit = chaseRange <= 0 ? range : chaseRange;
        return from.getWorld() != null && target.position().distanceToSqr(from.getX(), from.getY(), from.getZ()) > limit
                * limit;
    }

    /** @return the nearest player this guard considers an enemy, or null */
    private Entity findEnemy() {
        Entity self = npc.getEntity();
        if (self == null || heldItemPatterns.isEmpty() && groups.isEmpty())
            return null;

        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayer player : EntityUtil.getNearbyVisiblePlayers(self, range)) {
            if (player.isSpectator() || CitizensAPI.getNPCRegistry().isNPC(player))
                continue;
            double distance = player.distanceToSqr(self);
            if (distance < bestDistance && isEnemy(player)) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** @return whether this player matches any of the guard's target rules */
    public boolean isEnemy(ServerPlayer player) {
        if (!heldItemPatterns.isEmpty() && matchesHeldItem(player))
            return true;
        // null means "cannot say" - with no permission mod installed, or for an offline user. A guard must not treat an
        // unanswerable question as guilt, so only an explicit true counts.
        return !groups.isEmpty() && Boolean.TRUE.equals(PermissionUtil.inGroup(groups, player));
    }

    /**
     * Sentinel matched its held-item rules against the Bukkit material name, which for a modded item is the registry id
     * with the colon turned into an underscore — which is why the rules migrated from a real server read
     * {@code tacz_modern_kinetic_gun} rather than {@code tacz:modern_kinetic_gun}. Each rule is therefore tried against
     * three spellings of the item: the full id, the bare path, and the underscore form.
     * <p>
     * Matching is whole-string, not a substring search: the rules that came across list {@code tacz_ammo} and
     * {@code tacz_ammo_box} separately, so a substring match would make the first rule swallow the second item too.
     */
    private boolean matchesHeldItem(ServerPlayer player) {
        for (ItemStack stack : new ItemStack[] { player.getMainHandItem(), player.getOffhandItem() }) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String id = key.toString();
            String[] spellings = { id, key.getPath(), id.replace(':', '_') };
            for (Pattern pattern : heldItemPatterns) {
                for (String spelling : spellings) {
                    if (pattern.matcher(spelling).matches())
                        return true;
                }
            }
        }
        return false;
    }

    private void acquire(Entity enemy) {
        if (enemy == null || enemy == npc.getEntity())
            return;
        target = enemy;
        targetTicks = 0;
        npc.getNavigator().setTarget(enemy, true);
        say(warningText, enemy);
        alertSquad(enemy);
    }

    private void clearTarget() {
        target = null;
        targetTicks = 0;
        if (npc.getNavigator().isNavigating()) {
            npc.getNavigator().cancelNavigation();
        }
        // walk home rather than standing wherever the fight ended
        if (spawnPoint != null && spawnPoint.getWorld() != null) {
            npc.getNavigator().setTarget(spawnPoint);
        }
    }

    /** Tells every other guard in the same squad about this enemy, which is what Sentinel's squads are for. */
    private void alertSquad(Entity enemy) {
        if (squad == null || squad.isEmpty())
            return;
        for (NPC other : CitizensAPI.getNPCRegistry()) {
            if (other == npc || !other.isSpawned() || !other.hasTrait(SentinelTrait.class)) {
                continue;
            }
            SentinelTrait ally = other.getTraitNullable(SentinelTrait.class);
            if (ally != null && squad.equals(ally.getSquad()) && ally.getTarget() == null) {
                ally.acquire(enemy);
            }
        }
    }

    /**
     * Greets one nearby non-enemy per {@code greet_rate} ticks, and will not greet the same player again until their own
     * cooldown has run out — otherwise a player standing in range would be greeted forever.
     */
    private void greet() {
        greeted.replaceAll((uuid, remaining) -> remaining - 1);
        greeted.values().removeIf(remaining -> remaining <= 0);
        if (greetCooldown > 0) {
            greetCooldown--;
            return;
        }
        if (greetingText == null || greetingText.isEmpty() || npc.getEntity() == null)
            return;

        for (ServerPlayer player : EntityUtil.getNearbyVisiblePlayers(npc.getEntity(), greetRange)) {
            if (CitizensAPI.getNPCRegistry().isNPC(player) || greeted.containsKey(player.getUUID())
                    || isEnemy(player)) {
                continue;
            }
            greeted.put(player.getUUID(), greetRate);
            say(greetingText, player);
            greetCooldown = greetRate;
            return;
        }
    }

    private void say(String text, Entity to) {
        if (text == null || text.isEmpty() || !(to instanceof ServerPlayer player))
            return;
        Messaging.send(player.createCommandSourceStack(), npc.getName() + ": " + text);
    }

    @Override
    public void onDespawn() {
        target = null;
        targetTicks = 0;
    }

    public double getRange() {
        return range;
    }

    /** @return the held-item regexes this guard treats as hostile, in the order Sentinel stored them */
    public Set<String> getHeldItemRules() {
        return heldItemRaw;
    }

    public Set<String> getGroupRules() {
        return groups;
    }

    public Location getSpawnPoint() {
        return spawnPoint;
    }
}
