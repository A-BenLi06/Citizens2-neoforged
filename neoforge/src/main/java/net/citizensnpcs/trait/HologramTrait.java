package net.citizensnpcs.trait;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.joml.Vector3d;
import org.joml.Vector3f;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.math.Transformation;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.util.Util;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Display.BillboardConstraints;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Manages a set of <em>holograms</em> attached to the NPC — lines of text that follow it at some offset, plus the
 * floating nameplate an NPC gets when its name cannot be shown the ordinary way (too long, or coloured).
 * <p>
 * The design is upstream's: each line is itself an NPC in the temporary registry, whose entity is what the player sees.
 * A {@link HologramRenderer} decides which entity type that is and how it is positioned — either teleported to follow
 * the parent each tick, or mounted on it so the client interpolates.
 * <p>
 * Renderer choice is {@code npc.hologram.default-renderer}. All of upstream's text renderers are here:
 * {@code display} / {@code display_vehicle} (text display entities, the default), {@code interaction},
 * {@code areaeffectcloud}, {@code armorstand} / {@code armorstand_vehicle}. Upstream also branches on the server version
 * to pick a fallback; this port targets 1.21.1 alone, where every one of them exists, so those branches are gone.
 * <p>
 * The {@code <item:…>} syntax uses a native item riding an invisible point entity, or an explicitly supplied
 * {@link ItemDisplayRenderer}. Native registry IDs/components and legacy material/colour forms are supported.
 * {@code npc.use-packet-holograms}, which spawns entities per-viewer through {@code PacketNPC}, remains separate work.
 * Where upstream delegates persisted display properties to {@code DisplayTrait} / {@code TextDisplayTrait} via
 * {@code @Persist(reify = true)}, those fields sit directly on {@link TextDisplayRenderer} here. Because that annotation
 * flattens a trait's fields into the renderer's own key, the resulting on-disk shape is the same either way, and the
 * 39-file {@code versioned/} package is not dragged in for two of its members.
 */
@TraitName("hologramtrait")
public class HologramTrait extends Trait {
    private boolean customisedDefaultRenderer;
    private HologramRenderer defaultRenderer;
    private double lastEntityBbHeight = 0;
    private Location lastLoc;
    private boolean lastNameplateVisible;
    @Persist
    private double lineHeight = -1;
    private final List<HologramLine> lines = new ArrayList<>();
    private HologramLine nameLine;
    private final NPCRegistry registry = CitizensAPI.getTemporaryNPCRegistry();
    private int t;
    @Persist
    private int viewRange = -1;

    public HologramTrait() {
        super("hologramtrait");
        if (!Setting.DEFAULT_HOLOGRAM_RENDERER_SETTINGS.asMap().isEmpty()) {
            defaultRenderer = createRenderer(Setting.DEFAULT_HOLOGRAM_RENDERER.asString());
            DataKey key = new MemoryDataKey();
            key.setMap("renderer", Setting.DEFAULT_HOLOGRAM_RENDERER_SETTINGS.asMap());
            PersistenceLoader.load(defaultRenderer, key.getRelative("renderer"));
        }
    }

    /**
     * Adds a new hologram line which will displayed over an NPC's head.
     */
    public void addLine(String text) {
        lines.add(new HologramLine(text, true, -1, createHologramRenderer()));
        reset();
    }

    public void addLine(String text, HologramRenderer hr) {
        lines.add(new HologramLine(text, true, -1, hr));
        reset();
    }

    /**
     * Adds a hologram line that does not persist to disk and disappears after the given number of ticks. This is what
     * speech bubbles are built from.
     */
    public void addTemporaryLine(String text, int ticks) {
        lines.add(new HologramLine(text, false, ticks, createHologramRenderer()));
        reset();
    }

    public void addTemporaryLine(String text, int ticks, HologramRenderer hr) {
        lines.add(new HologramLine(text, false, ticks, hr));
        reset();
    }

    /**
     * Clears all hologram lines
     */
    public void clear() {
        for (HologramLine line : lines) {
            line.removeNPC();
        }
        lines.clear();
    }

    private HologramRenderer createHologramRenderer() {
        HologramRenderer renderer = createRenderer(Setting.DEFAULT_HOLOGRAM_RENDERER.asString());
        HologramRendererCreateEvent event = new HologramRendererCreateEvent(npc, renderer, false);
        NeoForge.EVENT_BUS.post(event);
        return event.getRenderer();
    }

    /**
     * The nameplate hologram. Upstream picks {@code armorstand_vehicle} on 1.20+ because a mounted armour stand tracks
     * the head far more closely than a teleported entity, and that is the only version this port runs on.
     */
    private HologramRenderer createNameRenderer() {
        HologramRenderer renderer = createRenderer("armorstand_vehicle");
        HologramRendererCreateEvent event = new HologramRendererCreateEvent(npc, renderer, true);
        NeoForge.EVENT_BUS.post(event);
        return event.getRenderer();
    }

    private HologramRenderer createRenderer(String setting) {
        if (defaultRenderer != null)
            return defaultRenderer.copy();

        switch (setting) {
            case "areaeffectcloud":
                return new AreaEffectCloudRenderer();
            case "armorstand":
                return new ArmorstandRenderer();
            case "armorstand_vehicle":
                return new ArmorstandVehicleRenderer();
            case "display_vehicle":
                return new TextDisplayVehicleRenderer();
            case "interaction":
                return new InteractionVehicleRenderer();
            case "display":
            default:
                return new TextDisplayRenderer();
        }
    }

    private double getHeight(int lineNumber) {
        double base = lastNameplateVisible ? 0 : -getLineHeight();
        for (int i = 0; i <= lineNumber; i++) {
            HologramLine line = lines.get(i);
            base += line.mb + getLineHeight();
            if (i != lineNumber) {
                base += line.mt;
            }
        }
        return base;
    }

    public Collection<Entity> getHologramEntities() {
        return lines.stream().flatMap(l -> l.renderer.getEntities().stream()).collect(Collectors.toList());
    }

    public Collection<HologramRenderer> getHologramRenderers() {
        return lines.stream().map(l -> l.renderer).collect(Collectors.toList());
    }

    /**
     * @return The line height between each hologram line, in blocks
     */
    public double getLineHeight() {
        return lineHeight == -1 ? Setting.DEFAULT_NPC_HOLOGRAM_LINE_HEIGHT.asDouble() : lineHeight;
    }

    /**
     * @return the hologram lines, in bottom-up order
     */
    public List<String> getLines() {
        return Lists.transform(lines, l -> l.text);
    }

    public Entity getNameEntity() {
        return nameLine == null || nameLine.renderer.getEntities().isEmpty() ? null
                : nameLine.renderer.getEntities().iterator().next();
    }

    public HologramRenderer getNameRenderer() {
        return nameLine == null ? null : nameLine.renderer;
    }

    public HologramRenderer getTemplateRenderer() {
        customisedDefaultRenderer = true;
        return defaultRenderer == null ? defaultRenderer = new TextDisplayRenderer() : defaultRenderer;
    }

    public int getViewRange() {
        return viewRange;
    }

    public void insertLine(int idx, String text) {
        lines.add(idx, new HologramLine(text, true, -1, createHologramRenderer()));
        reset();
    }

    /**
     * Whether the parent NPC is currently something a player could see. An invisible NPC should not trail visible
     * holograms behind it.
     */
    private boolean isVisible(Entity entity) {
        if (entity instanceof LivingEntity living)
            return !living.isInvisible() && !living.hasEffect(MobEffects.INVISIBILITY);
        return entity != null;
    }

    @Override
    public void load(DataKey root) {
        clear();
        if (!root.getString("default_renderer.type", "").isEmpty()) {
            customisedDefaultRenderer = true;
            defaultRenderer = null;
            defaultRenderer = PersistenceLoader.load(createRenderer(root.getString("default_renderer.type")),
                    root.getRelative("default_renderer"));
        }
        for (DataKey key : root.getRelative("lines").getIntegerSubKeys()) {
            String text = key.keyExists("text") ? key.getString("text") : key.getString("");
            HologramRenderer renderer = HologramItem.containsItem(text)
                    && key.getString("renderer.type", "").equals("item_display")
                    ? new ItemDisplayRenderer() : createHologramRenderer();
            HologramLine line = new HologramLine(text, true, -1, renderer);
            line.mt = key.keyExists("margin.top") ? key.getDouble("margin.top") : line.mt;
            line.mb = key.keyExists("margin.bottom") ? key.getDouble("margin.bottom") : line.mb;
            if (key.keyExists("renderer")) {
                PersistenceLoader.load(line.renderer, key.getRelative("renderer"));
            }
            lines.add(line);
        }
    }

    @Override
    public void onDespawn() {
        reset();
    }

    @Override
    public void onRemove() {
        reset();
    }

    /**
     * Tears down every hologram entity. They are rebuilt on the next tick, which is also how a changed line height or
     * margin takes effect.
     */
    private void reset() {
        for (HologramLine line : lines) {
            line.removeNPC();
        }
        if (nameLine != null) {
            nameLine.removeNPC();
            nameLine = null;
        }
    }

    public boolean onSeenByPlayer(ServerPlayer player) {
        return npc.isSpawned() && isVisible(npc.getEntity());
    }

    @Override
    public void onSpawn() {
        if (!npc.isSpawned())
            return;
        lastNameplateVisible = Boolean
                .parseBoolean(npc.data().<Object> get(NPC.Metadata.NAMEPLATE_VISIBLE, true).toString());
    }

    /**
     * Removes the line at the specified index
     */
    public void removeLine(int idx) {
        if (idx < 0 || idx >= lines.size())
            return;
        lines.remove(idx).removeNPC();
    }

    @Override
    public void run() {
        if (!npc.isSpawned() || !isVisible(npc.getEntity())) {
            reset();
            return;
        }
        boolean nameplateVisible = Boolean
                .parseBoolean(npc.data().<Object> get(NPC.Metadata.NAMEPLATE_VISIBLE, true).toString());
        if (npc.requiresNameHologram()) {
            if (nameLine != null && !nameplateVisible) {
                nameLine.removeNPC();
                nameLine = null;
            } else if (nameLine == null && nameplateVisible) {
                nameLine = new HologramLine(npc.getRawName(), createNameRenderer());
            }
        }
        Entity entity = npc.getEntity();
        Location npcLoc = Location.fromEntity(entity);
        boolean updatePosition = Setting.HOLOGRAM_ALWAYS_UPDATE_POSITION.asBoolean() || lastLoc == null
                || lastLoc.getWorld() != npcLoc.getWorld() || lastNameplateVisible != nameplateVisible
                || Math.abs(lastEntityBbHeight - entity.getBbHeight()) >= 0.05 || lastLoc.distance(npcLoc) >= 0.001;
        boolean updateText = false;
        Vector3d offset = new Vector3d(0, 0, 0);

        // the jitter spreads the text refresh of many NPCs across ticks instead of bunching it on one
        if (t++ >= Setting.HOLOGRAM_UPDATE_RATE.asTicks() + Util.getFastRandom().nextInt(3)) {
            t = 0;
            updateText = true;
        }
        lastNameplateVisible = nameplateVisible;

        if (updatePosition) {
            lastLoc = npcLoc.clone();
            lastEntityBbHeight = entity.getBbHeight();
        }
        if (nameLine != null) {
            if (updateText) {
                nameLine.setText(npc.getRawName());
            }
            if (updatePosition || nameLine.renderer.getEntities().isEmpty()) {
                nameLine.render(offset);
                boolean sneaking = entity.isShiftKeyDown();
                nameLine.renderer.getEntities().forEach(e -> e.setShiftKeyDown(sneaking));
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            HologramLine line = lines.get(i);

            if (line.ticks > 0 && --line.ticks == 0) {
                lines.remove(i--).removeNPC();
                continue;
            }
            if (updatePosition || line.renderer.getEntities().isEmpty()) {
                offset.y = getHeight(i);
                line.render(offset);
            }
            if (updateText) {
                line.setText(line.text);
            }
        }
    }

    @Override
    public void save(DataKey root) {
        root.removeKey("default_renderer");
        if (defaultRenderer != null && customisedDefaultRenderer) {
            root.setString("default_renderer.type", rendererTypeName(defaultRenderer));
            PersistenceLoader.save(defaultRenderer, root.getRelative("default_renderer"));
        }
        root.removeKey("lines");
        int i = 0;
        for (HologramLine line : lines) {
            if (!line.persist)
                continue;
            PersistenceLoader.save(line.renderer, root.getRelative("lines." + i + ".renderer"));
            if (line.renderer instanceof ItemDisplayRenderer) root.setString("lines." + i + ".renderer.type", "item_display");
            root.setString("lines." + i + ".text", line.text);
            root.setDouble("lines." + i + ".margin.top", line.mt);
            root.setDouble("lines." + i + ".margin.bottom", line.mb);
            i++;
        }
    }

    /**
     * The {@code default-renderer} name a renderer was built from. Upstream only round-trips three of them; every
     * renderer is named here so a saved custom renderer reloads as the same type it was.
     */
    private static String rendererTypeName(HologramRenderer renderer) {
        if (renderer instanceof TextDisplayVehicleRenderer)
            return "display_vehicle";
        if (renderer instanceof TextDisplayRenderer)
            return "display";
        if (renderer instanceof AreaEffectCloudRenderer)
            return "areaeffectcloud";
        if (renderer instanceof InteractionVehicleRenderer)
            return "interaction";
        if (renderer instanceof ArmorstandVehicleRenderer)
            return "armorstand_vehicle";
        if (renderer instanceof ArmorstandRenderer)
            return "armorstand";
        return "";
    }

    /**
     * Sets the hologram line at a specific index
     */
    public void setLine(int idx, String text) {
        if (idx == lines.size()) {
            addLine(text);
            return;
        }
        lines.get(idx).setText(text);
    }

    /**
     * Sets the line height in blocks
     *
     * @see #getLineHeight()
     */
    public void setLineHeight(double height) {
        lineHeight = height;
        reset();
    }

    /**
     * Sets the margin of a line at a specific index
     *
     * @param type
     *            the margin type, {@code top} or {@code bottom}
     */
    public void setMargin(int idx, String type, double margin) {
        if (type.equalsIgnoreCase("top")) {
            lines.get(idx).mt = margin;
        } else if (type.equalsIgnoreCase("bottom")) {
            lines.get(idx).mb = margin;
        }
        reset();
    }

    public void setViewRange(int range) {
        viewRange = range;
        for (HologramLine line : lines) {
            if (line.renderer instanceof SingleEntityHologramRenderer single) single.setViewRange(range);
        }
        if (nameLine != null && nameLine.renderer instanceof SingleEntityHologramRenderer single)
            single.setViewRange(range);
        reset();
    }

    /**
     * An area effect cloud with no radius and an invisible particle. Upstream calls this the safest option: the entity
     * has no hitbox and no client-side rendering of its own beyond the nameplate.
     */
    public static class AreaEffectCloudRenderer extends SingleEntityHologramRenderer {
        private boolean configured;

        @Override
        public HologramRenderer copy() {
            return new AreaEffectCloudRenderer();
        }

        @Override
        protected NPC createNPC(NPC base, String name, Vector3d offset) {
            NPC npc = registry().createNPC(EntityType.AREA_EFFECT_CLOUD, name);
            configured = false;
            return npc;
        }

        @Override
        protected void render0(NPC npc, Vector3d offset) {
            if (!(hologram.getEntity() instanceof AreaEffectCloud cloud))
                return;
            if (!configured) {
                cloud.setRadius(0);
                cloud.setParticle(new BlockParticleOption(ParticleTypes.BLOCK_MARKER, Blocks.AIR.defaultBlockState()));
                // vanilla clouds expire; a hologram must not
                cloud.setDuration(Integer.MAX_VALUE);
                cloud.setWaitTime(0);
                configured = true;
            }
            Entity parent = npc.getEntity();
            teleport(cloud, parent, offset.x, offset.y + parent.getBbHeight() - 0.5, offset.z);
        }
    }

    /** An invisible marker armour stand carrying the name, teleported to follow the NPC. */
    public static class ArmorstandRenderer extends SingleEntityHologramRenderer {
        @Override
        public HologramRenderer copy() {
            return new ArmorstandRenderer();
        }

        @Override
        protected NPC createNPC(NPC base, String name, Vector3d offset) {
            NPC npc = registry().createNPC(EntityType.ARMOR_STAND, name);
            npc.getOrAddTrait(ArmorStandTrait.class).setAsHelperEntityWithName(base);
            return npc;
        }

        @Override
        protected void render0(NPC npc, Vector3d offset) {
            Entity parent = npc.getEntity();
            teleport(hologram.getEntity(), parent, offset.x, offset.y + parent.getBbHeight(), offset.z);
        }
    }

    /**
     * Same armour stand, but mounted on the NPC instead of teleported. The client then moves the nameplate with the NPC
     * itself, which is smoother and is why upstream prefers it for the nameplate on 1.20+.
     */
    public static class ArmorstandVehicleRenderer extends SingleEntityHologramRenderer {
        @Override
        public HologramRenderer copy() {
            return new ArmorstandVehicleRenderer();
        }

        @Override
        protected NPC createNPC(NPC base, String name, Vector3d offset) {
            NPC npc = registry().createNPC(EntityType.ARMOR_STAND, name);
            npc.getOrAddTrait(ArmorStandTrait.class).setAsHelperEntityWithName(base);
            return npc;
        }

        @Override
        protected void render0(NPC npc, Vector3d offset) {
            mountOnParent(npc);
        }
    }

    class HologramLine {
        double mb, mt;
        boolean persist;
        HologramRenderer renderer;
        String text;
        int ticks;

        public HologramLine(String text, boolean persist, int ticks, HologramRenderer hr) {
            if (HologramItem.containsItem(text)) {
                mb = 0.21;
                mt = 0.07;
            }
            this.persist = persist;
            this.ticks = ticks;
            renderer = hr;
            if (renderer instanceof SingleEntityHologramRenderer sr) {
                sr.setViewRange(viewRange);
                sr.setRegistry(registry);
            }
            setText(text);
        }

        public HologramLine(String text, HologramRenderer renderer) {
            this(text, false, -1, renderer);
        }

        public void removeNPC() {
            renderer.destroy();
        }

        public void render(Vector3d vector3d) {
            renderer.render(npc, vector3d);
        }

        public void setText(String text) {
            this.text = text == null ? "" : text;
            boolean item = HologramItem.containsItem(this.text);
            if (item != (renderer instanceof ItemRenderer)) {
                renderer.destroy();
                renderer = item ? new ItemRenderer() : createHologramRenderer();
                if (renderer instanceof SingleEntityHologramRenderer single) {
                    single.setViewRange(viewRange);
                    single.setRegistry(registry);
                }
                if (mb == (item ? 0 : 0.21)) mb = item ? 0.21 : 0;
                if (mt == (item ? 0 : 0.07)) mt = item ? 0.07 : 0;
                lastLoc = null;
            }
            renderer.updateText(npc, this.text);
        }
    }

    /**
     * API for rendering holograms.
     */
    public static interface HologramRenderer {
        default HologramRenderer copy() {
            try {
                return getClass().getConstructor().newInstance();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        /**
         * Destroy/teardown any rendered holograms.
         */
        void destroy();

        /**
         * @return any entities this renderer has spawned
         */
        Collection<Entity> getEntities();

        /**
         * The text as one specific viewer should see it, with placeholders resolved for them.
         */
        String getPerPlayerText(NPC hologram, ServerPlayer viewer);

        default NPC getTemplateNPC() {
            return null;
        }

        default boolean isSneaking(NPC npc, ServerPlayer player) {
            return npc.isSpawned() && npc.getEntity().isShiftKeyDown();
        }

        /**
         * Called when the hologram is first seen by a player.
         */
        default void onSeenByPlayer(NPC hologram, ServerPlayer player) {
        }

        /**
         * Render the hologram at a given offset in blocks. Any underlying hologram NPCs are spawned at this point.
         */
        void render(NPC parent, Vector3d offset);

        /**
         * Update the hologram text. Called before {@link #render(NPC, Vector3d)}.
         */
        void updateText(NPC parent, String text);
    }

    /**
     * Lets other code substitute its own renderer as each one is created.
     */
    public static class HologramRendererCreateEvent extends NPCEvent {
        private final boolean nameRenderer;
        private HologramRenderer renderer;

        protected HologramRendererCreateEvent(NPC npc, HologramRenderer renderer, boolean nameRenderer) {
            super(npc);
            this.renderer = renderer;
            this.nameRenderer = nameRenderer;
        }

        public HologramRenderer getRenderer() {
            return renderer;
        }

        public boolean isNameRenderer() {
            return nameRenderer;
        }

        public void setRenderer(HologramRenderer renderer) {
            Objects.requireNonNull(renderer);
            this.renderer = renderer;
        }
    }

    /**
     * An interaction entity mounted on the NPC. Its nameplate sits where a player's would, so this matches vanilla
     * nametag placement more closely than the alternatives.
     */
    public static class InteractionVehicleRenderer extends SingleEntityHologramRenderer {
        @Override
        public HologramRenderer copy() {
            return new InteractionVehicleRenderer();
        }

        @Override
        protected NPC createNPC(NPC base, String name, Vector3d offset) {
            return registry().createNPC(EntityType.INTERACTION, name);
        }

        @Override
        public void render0(NPC npc, Vector3d offset) {
            if (hologram.getEntity() instanceof Interaction interaction) {
                // the nameplate of an interaction entity floats at its top, so the height carries the offset
                interaction.setWidth(0.01f);
                interaction.setHeight((float) Math.max(0.01, offset.y));
                interaction.setResponse(false);
            }
            mountOnParent(npc);
        }
    }

    /**
     * A helper class that models a hologram as a single entity representing one line.
     */
    public abstract static class SingleEntityHologramRenderer implements HologramRenderer {
        protected NPC hologram;
        private NPCRegistry registry;
        private int spawnWaitTicks;
        protected String text;
        private int viewRange = -1;

        protected abstract NPC createNPC(NPC base, String text, Vector3d offset);

        @Override
        public void destroy() {
            if (hologram != null) {
                hologram.destroy();
                hologram = null;
            }
        }

        @Override
        public Collection<Entity> getEntities() {
            return hologram != null && hologram.isSpawned() ? ImmutableList.of(hologram.getEntity())
                    : Collections.emptyList();
        }

        @Override
        public String getPerPlayerText(NPC npc, ServerPlayer viewer) {
            return text == null ? null
                    : Placeholders.replace(text, viewer == null ? null : viewer.createCommandSourceStack(), npc);
        }

        @Override
        public NPC getTemplateNPC() {
            return hologram != null ? hologram : createNPC(null, "", new Vector3d(0, 0, 0));
        }

        protected NPCRegistry registry() {
            return registry == null ? registry = CitizensAPI.getTemporaryNPCRegistry() : registry;
        }

        @Override
        public void render(NPC npc, Vector3d offset) {
            if (getEntities().isEmpty() && spawnWaitTicks-- <= 0) {
                destroy();
                spawnHologram(npc, offset);
                spawnWaitTicks = 5;
            }
            if (hologram == null || !hologram.isSpawned())
                return;
            render0(npc, offset);
        }

        protected abstract void render0(NPC npc, Vector3d offset);

        /** Moves a hologram entity to the parent's position plus an offset. */
        protected static void teleport(Entity hologramEntity, Entity parent, double dx, double dy, double dz) {
            hologramEntity.teleportTo(parent.getX() + dx, parent.getY() + dy, parent.getZ() + dz);
        }

        /**
         * Mounts the hologram entity on the parent, once. Vanilla keeps the passenger positioned from then on.
         */
        protected void mountOnParent(NPC parent) {
            Entity hologramEntity = hologram.getEntity();
            if (hologramEntity != null && hologramEntity.getVehicle() == null && parent.isSpawned()) {
                hologramEntity.startRiding(parent.getEntity(), true);
            }
        }

        public void setRegistry(NPCRegistry registry) {
            this.registry = registry;
        }

        public void setViewRange(int range) {
            viewRange = range;
        }

        protected void spawnHologram(NPC npc, Vector3d offset) {
            hologram = createNPC(npc, Placeholders.replace(text, null, npc), offset);
            if (hologram == null) return;
            configureHologram(hologram, npc);
            Entity parent = npc.getEntity();
            Location at = Location.fromEntity(parent).clone();
            at.setX(at.getX() + offset.x);
            at.setY(at.getY() + offset.y + parent.getBbHeight());
            at.setZ(at.getZ() + offset.z);
            hologram.spawn(at);
        }

        protected void configureHologram(NPC child, NPC parent) {
            if (!child.hasTrait(ClickRedirectTrait.class)) {
                child.addTrait(new ClickRedirectTrait(parent));
            }
            child.data().set(NPC.Metadata.HOLOGRAM_RENDERER, this);
            if (viewRange != -1) {
                child.data().set(NPC.Metadata.TRACKING_RANGE, viewRange);
            } else if (parent.data().has(NPC.Metadata.TRACKING_RANGE)) {
                child.data().set(NPC.Metadata.TRACKING_RANGE, parent.data().get(NPC.Metadata.TRACKING_RANGE));
            }
        }

        @Override
        public void updateText(NPC npc, String raw) {
            if (text != null && text.equals(raw))
                return;
            text = raw;
            if (hologram == null)
                return;
            String updatedName = Placeholders.replace(text, null, npc);
            if (hologram.isSpawned()) {
                hologram.getEntity().setCustomName(null);
            }
            hologram.setName(updatedName);
            if (!Placeholders.containsPlaceholders(text)) {
                hologram.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, !Messaging.stripColor(text).isEmpty());
            }
        }
    }

    /**
     * A text display entity, the default. Unlike every other renderer this shows real text rather than a nameplate, so
     * it honours line width, background colour, alignment and the see-through flag.
     */
    public static class TextDisplayRenderer extends SingleEntityHologramRenderer {
        @Persist
        protected String billboard = BillboardConstraints.CENTER.name();
        @Persist
        protected Integer bgcolor;
        @Persist
        protected Integer interpolationDelay = 0;
        @Persist
        protected Integer interpolationDuration = 0;
        @Persist
        protected Integer lineWidth;
        @Persist
        protected Boolean seeThrough = true;
        @Persist
        protected Boolean shadowed;
        @Persist
        protected Vector3f scale;

        @Override
        public HologramRenderer copy() {
            TextDisplayRenderer copy = new TextDisplayRenderer();
            copyInto(copy);
            return copy;
        }

        protected void copyInto(TextDisplayRenderer copy) {
            copy.billboard = billboard;
            copy.bgcolor = bgcolor;
            copy.interpolationDelay = interpolationDelay;
            copy.interpolationDuration = interpolationDuration;
            copy.lineWidth = lineWidth;
            copy.seeThrough = seeThrough;
            copy.shadowed = shadowed;
            copy.scale = scale == null ? null : new Vector3f(scale);
        }

        @Override
        protected NPC createNPC(NPC base, String name, Vector3d offset) {
            NPC hologram = registry().createNPC(EntityType.TEXT_DISPLAY, "");
            hologram.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
            hologram.data().set(NPC.Metadata.TEXT_DISPLAY_COMPONENT,
                    Messaging.minecraftComponentFromRawMessage(name));
            return hologram;
        }

        /** Applies the persisted display properties. Called every render since the entity may have been respawned. */
        protected void applyDisplayProperties(Display.TextDisplay display) {
            BillboardConstraints constraint;
            try {
                constraint = BillboardConstraints.valueOf(billboard.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                constraint = BillboardConstraints.CENTER;
            }
            display.setBillboardConstraints(constraint);
            display.setTransformationInterpolationDelay(interpolationDelay == null ? 0 : interpolationDelay);
            display.setTransformationInterpolationDuration(interpolationDuration == null ? 0 : interpolationDuration);
            if (lineWidth != null) {
                display.setLineWidth(lineWidth);
            }
            if (bgcolor != null) {
                display.setBackgroundColor(bgcolor);
            }
            byte flags = display.getFlags();
            flags = setFlag(flags, Display.TextDisplay.FLAG_SEE_THROUGH, seeThrough != null && seeThrough);
            flags = setFlag(flags, Display.TextDisplay.FLAG_SHADOW, shadowed != null && shadowed);
            display.setFlags(flags);
        }

        private static byte setFlag(byte flags, byte flag, boolean on) {
            return (byte) (on ? flags | flag : flags & ~flag);
        }

        /**
         * Matches the hologram's scale to the NPC's, so a scaled-up NPC does not get a tiny nameplate.
         */
        protected Transformation transformationFor(NPC base, double translationY) {
            Vector3f size = new Vector3f(1, 1, 1);
            if (base.getEntity() instanceof LivingEntity living) {
                var attribute = living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE);
                if (attribute != null) {
                    float value = (float) attribute.getValue();
                    size.set(value, value, value);
                }
            }
            if (scale != null) {
                size.mul(scale);
            }
            return new Transformation(new Vector3f(0, (float) translationY, 0), null, size, null);
        }

        @Override
        public void render0(NPC base, Vector3d offset) {
            if (!(hologram.getEntity() instanceof Display.TextDisplay display))
                return;
            applyDisplayProperties(display);
            display.setTransformation(transformationFor(base, 0));
            Entity parent = base.getEntity();
            teleport(display, parent, offset.x, offset.y + parent.getBbHeight() + 0.2f, offset.z);
        }

        @Override
        public void updateText(NPC npc, String raw) {
            text = raw;
            if (hologram == null)
                return;
            Component component = Messaging.minecraftComponentFromRawMessage(Placeholders.replace(text, null, npc));
            hologram.data().set(NPC.Metadata.TEXT_DISPLAY_COMPONENT, component);
            if (hologram.getEntity() instanceof Display.TextDisplay display) {
                display.setText(component);
            }
        }
    }

    /**
     * The text display mounted on the NPC rather than teleported. The offset moves into the display's transformation,
     * since a passenger's own position is controlled by its vehicle.
     */
    public static class TextDisplayVehicleRenderer extends TextDisplayRenderer {
        @Override
        public HologramRenderer copy() {
            TextDisplayVehicleRenderer copy = new TextDisplayVehicleRenderer();
            copyInto(copy);
            return copy;
        }

        @Override
        public void render0(NPC npc, Vector3d offset) {
            if (!(hologram.getEntity() instanceof Display.TextDisplay display))
                return;
            applyDisplayProperties(display);
            display.setTransformation(transformationFor(npc, offset.y + 0.2f));
            mountOnParent(npc);
        }
    }

    /** A native item passenger retains vanilla item presentation without falling, merging or being picked up. */
    public static class ItemRenderer extends SingleEntityHologramRenderer {
        protected NPC itemNPC;
        private String resolvedText;
        private HologramItem.Definition definition;

        @Override public HologramRenderer copy() { return new ItemRenderer(); }

        @Override public NPC getTemplateNPC() { return itemNPC; }

        @Override public String getPerPlayerText(NPC npc, ServerPlayer viewer) { return ""; }

        @Override public void updateText(NPC parent, String raw) {
            text = raw;
            String resolved = Placeholders.replace(raw, null, parent);
            if (Objects.equals(resolved, resolvedText)) return;
            resolvedText = resolved;
            destroy();
            var server = ServerLifecycleHooks.getCurrentServer();
            definition = server == null ? null : HologramItem.parse(resolved, server.registryAccess());
            if (server != null && definition == null)
                Messaging.severe("Could not resolve hologram item:", resolved);
        }

        protected NPC createItem(NPC parent, EntityType<?> type) {
            if (definition == null) return null;
            NPC item = registry().createNPCUsingItem(type, "", definition.stack().copy());
            item.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
            if (definition.color() != null) item.getOrAddTrait(ScoreboardTrait.class).setColor(definition.color());
            configureHologram(item, parent);
            return item;
        }

        @Override protected NPC createNPC(NPC parent, String name, Vector3d offset) {
            itemNPC = createItem(parent, EntityType.ITEM);
            if (itemNPC == null) return null;
            NPC mount = registry().createNPC(EntityType.ARMOR_STAND, "");
            mount.getOrAddTrait(ArmorStandTrait.class).setAsPointEntity();
            return mount;
        }

        @Override protected void spawnHologram(NPC parent, Vector3d offset) {
            try {
                super.spawnHologram(parent, offset);
                if (hologram == null || !hologram.isSpawned() || itemNPC == null) {
                    destroy();
                    return;
                }
                if (!itemNPC.spawn(Location.fromEntity(hologram.getEntity()))
                        || !itemNPC.getEntity().startRiding(hologram.getEntity(), true)) destroy();
            } catch (RuntimeException | Error failure) {
                destroy();
                throw failure;
            }
        }

        @Override public Collection<Entity> getEntities() {
            return hologram != null && hologram.isSpawned() && itemNPC != null && itemNPC.isSpawned()
                    ? List.of(hologram.getEntity(), itemNPC.getEntity()) : Collections.emptyList();
        }

        @Override protected void render0(NPC parent, Vector3d offset) {
            Entity anchor = hologram.getEntity();
            teleport(anchor, parent.getEntity(), offset.x, offset.y + parent.getEntity().getBbHeight(), offset.z);
            Entity item = itemNPC.getEntity();
            if (item.getVehicle() != anchor) item.startRiding(anchor, true);
            anchor.positionRider(item);
            if (item instanceof ItemEntity drop) {
                drop.setNeverPickUp();
                drop.setUnlimitedLifetime();
            }
        }

        @Override public void destroy() {
            if (itemNPC != null && itemNPC != hologram) itemNPC.destroy();
            itemNPC = null;
            super.destroy();
        }
    }

    /** Optional native item display, mounted on the parent with the line offset in its transformation. */
    public static class ItemDisplayRenderer extends ItemRenderer {
        @Override public HologramRenderer copy() { return new ItemDisplayRenderer(); }

        @Override protected NPC createNPC(NPC parent, String name, Vector3d offset) {
            return itemNPC = createItem(parent, EntityType.ITEM_DISPLAY);
        }

        @Override protected void spawnHologram(NPC parent, Vector3d offset) {
            // The display is both the hologram and the item; it has no separate anchor/passenger to spawn.
            hologram = createNPC(parent, text, offset);
            if (hologram != null && !hologram.spawn(parent.getStoredLocation())) destroy();
        }

        @Override public Collection<Entity> getEntities() {
            return hologram != null && hologram.isSpawned() ? List.of(hologram.getEntity()) : Collections.emptyList();
        }

        @Override protected void render0(NPC parent, Vector3d offset) {
            if (hologram.getEntity() instanceof Display.ItemDisplay display) {
                Transformation transform = Display.createTransformation(display.getEntityData());
                transform.getTranslation().y = (float) offset.y + 0.1f;
                display.setTransformation(transform);
                mountOnParent(parent);
            }
        }
    }
}
