package net.citizensnpcs.trait.versioned;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.primitives.Doubles;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.EntityUtil;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.api.util.TextParser;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/**
 * A boss bar shown above the screen of players near the NPC.
 * <p>
 * A wither or an ender dragon already owns a bar, so the trait drives that one instead of adding a second — vanilla keeps
 * both fields private, and Bukkit reaches them through its own wrapper, which is why upstream needs reflection there.
 * <p>
 * Bukkit's bar style names differ from vanilla's ({@code SEGMENTED_10} against {@code NOTCHED_10}), so the stored value
 * goes through a table and both spellings are accepted. The colour names are identical. The three flags are a list on
 * disk, as upstream, rather than the three booleans vanilla models them as.
 */
@TraitName("bossbar")
public class BossBarTrait extends Trait {
    private ServerBossEvent activeBar;
    @Persist
    private BossEvent.BossBarColor color = BossEvent.BossBarColor.PURPLE;
    private List<BarFlag> flags = new ArrayList<>();
    private Supplier<Double> progressProvider;
    @Persist
    private int range = -1;
    private BossEvent.BossBarOverlay style = BossEvent.BossBarOverlay.PROGRESS;
    @Persist
    private String title = "";
    @Persist
    private String track;
    @Persist
    private String viewPermission;
    @Persist
    private boolean visible = true;

    public BossBarTrait() {
        super("bossbar");
    }

    public BossEvent.BossBarColor getColor() {
        return color;
    }

    public List<BarFlag> getFlags() {
        return flags;
    }

    public int getRange() {
        return range;
    }

    public BossEvent.BossBarOverlay getStyle() {
        return style;
    }

    public String getTitle() {
        return title;
    }

    public String getTrackingVariable() {
        return track;
    }

    public String getViewPermission() {
        return viewPermission;
    }

    public boolean isVisible() {
        return visible;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        style = parseStyle(key.getString("style"));
        flags = new ArrayList<>();
        for (DataKey sub : key.getRelative("flags").getSubKeys()) {
            BarFlag flag = parseFlag(sub.getString(""));
            if (flag != null) {
                flags.add(flag);
            }
        }
    }

    @Override
    public void save(DataKey key) {
        key.setString("style", BUKKIT_STYLE_NAMES.getOrDefault(style, style.name()));
        key.removeKey("flags");
        for (int i = 0; i < flags.size(); i++) {
            key.setString("flags." + i, flags.get(i).name());
        }
    }

    /**
     * The bar this NPC drives: the entity's own for a wither or a dragon, otherwise one of ours.
     */
    private ServerBossEvent getBar() {
        ServerBossEvent own = ownBarOf();
        if (own != null) {
            // the entity's own bar took over, so drop any bar this trait had made earlier
            discardOwnBar();
            return own;
        }
        if (activeBar == null) {
            activeBar = new ServerBossEvent(TextParser.parse(npc.getFullName()), color, style);
        }
        return activeBar;
    }

    /**
     * @return the boss bar the entity itself owns, or null when it has none
     */
    private ServerBossEvent ownBarOf() {
        if (npc.getEntity() instanceof WitherBoss wither)
            return wither.bossEvent;
        if (npc.getEntity() instanceof EnderDragon dragon)
            // null everywhere but the End, where the dragon fight owns the bar
            return dragon.getDragonFight() == null ? null : dragon.getDragonFight().dragonEvent;
        return null;
    }

    private void discardOwnBar() {
        if (activeBar == null)
            return;
        activeBar.removeAllPlayers();
        activeBar.setVisible(false);
        activeBar = null;
    }

    @Override
    public void onDespawn() {
        discardOwnBar();
    }

    @Override
    public void onRemove() {
        discardOwnBar();
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        ServerBossEvent bar = getBar();
        if (bar == null)
            return;
        if (track != null && !track.isEmpty()) {
            if (!applyTracking(bar))
                return;
        }
        bar.setName(TextParser.parse(title));
        bar.setVisible(visible);
        if (progressProvider != null) {
            bar.setProgress(progressProvider.get().floatValue());
        }
        if (style != null) {
            bar.setOverlay(style);
        }
        if (color != null) {
            bar.setColor(color);
        }
        bar.setDarkenScreen(flags.contains(BarFlag.DARKEN_SKY));
        bar.setPlayBossMusic(flags.contains(BarFlag.PLAY_BOSS_MUSIC));
        bar.setCreateWorldFog(flags.contains(BarFlag.CREATE_FOG));

        bar.removeAllPlayers();
        for (ServerPlayer player : EntityUtil.getNearbyVisiblePlayers(npc.getEntity(),
                range > 0 ? range : Setting.BOSSBAR_RANGE.asInt())) {
            if (viewPermission != null && !PermissionUtil.hasPermission(player, viewPermission)) {
                continue;
            }
            bar.addPlayer(player);
        }
    }

    /**
     * @return false when the tracked variable did not resolve to a number, in which case the rest of the update is
     *         skipped so a transient placeholder failure does not blank the bar
     */
    private boolean applyTracking(ServerBossEvent bar) {
        if (track.equalsIgnoreCase("health")) {
            if (npc.getEntity() instanceof LivingEntity living) {
                double max = living.getAttributeValue(Attributes.MAX_HEALTH);
                bar.setProgress(max <= 0 ? 0 : (float) (living.getHealth() / max));
            }
            return true;
        }
        String replaced = Placeholders.replace(track,
                npc.getEntity() instanceof ServerPlayer player ? player : null);
        Double number = Doubles.tryParse(replaced);
        if (number == null)
            return false;
        if (number >= 1 && number <= 100) {
            number /= 100.0;
        }
        bar.setProgress((float) Math.max(0, Math.min(1, number)));
        return true;
    }

    public void setColor(BossEvent.BossBarColor color) {
        this.color = color;
    }

    public void setFlags(Collection<BarFlag> flags) {
        this.flags = new ArrayList<>(flags);
    }

    public void setProgressProvider(Supplier<Double> provider) {
        progressProvider = provider;
    }

    public void setRange(int range) {
        this.range = range;
    }

    public void setStyle(BossEvent.BossBarOverlay style) {
        this.style = style;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setTrackVariable(String variable) {
        track = variable;
    }

    public void setViewPermission(String viewPermission) {
        this.viewPermission = viewPermission;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /** Accepts either spelling; vanilla's default overlay for an empty or unrecognised value. */
    public static BossEvent.BossBarOverlay parseStyle(String raw) {
        if (raw == null || raw.isEmpty())
            return BossEvent.BossBarOverlay.PROGRESS;
        String upper = raw.toUpperCase(Locale.ROOT);
        for (Map.Entry<BossEvent.BossBarOverlay, String> entry : BUKKIT_STYLE_NAMES.entrySet()) {
            if (entry.getValue().equals(upper))
                return entry.getKey();
        }
        try {
            return BossEvent.BossBarOverlay.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            return BossEvent.BossBarOverlay.PROGRESS;
        }
    }

    public static BarFlag parseFlag(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        try {
            return BarFlag.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The three flags under Bukkit's names, which is what saves hold. Vanilla models them as three separate booleans on
     * the bar rather than as a set.
     */
    public enum BarFlag {
        CREATE_FOG,
        DARKEN_SKY,
        PLAY_BOSS_MUSIC
    }

    /** Bukkit's bar style names for the five vanilla overlays. */
    private static final Map<BossEvent.BossBarOverlay, String> BUKKIT_STYLE_NAMES = Map.of(
            BossEvent.BossBarOverlay.PROGRESS, "SOLID", BossEvent.BossBarOverlay.NOTCHED_6, "SEGMENTED_6",
            BossEvent.BossBarOverlay.NOTCHED_10, "SEGMENTED_10", BossEvent.BossBarOverlay.NOTCHED_12, "SEGMENTED_12",
            BossEvent.BossBarOverlay.NOTCHED_20, "SEGMENTED_20");
}
