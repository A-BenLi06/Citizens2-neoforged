package net.citizensnpcs;

import net.citizensnpcs.util.LuckPermsGroups;
import net.citizensnpcs.util.ParadigmGroups;
import net.citizensnpcs.util.ParadigmPermissions;
import net.citizensnpcs.api.npc.templates.TemplateRegistry;
import net.citizensnpcs.commands.TemplateCommands;
import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.commands.AdminCommands;
import net.citizensnpcs.npc.skin.Skin;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.CitizensPlugin;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.ai.tree.BehaviorRegistry;
import net.citizensnpcs.npc.ai.tree.CitizensBehaviorRegistry;
import net.citizensnpcs.api.expr.ExpressionRegistry;
import net.citizensnpcs.api.expr.JSR223Engine;
import net.citizensnpcs.npc.ai.tree.MolangEngine;
import net.citizensnpcs.trait.shop.StoredShops;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCDataStore;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.npc.SimpleNPCDataStore;
import net.citizensnpcs.api.trait.TraitFactory;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.Translator;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.api.util.schedulers.Schedulers;
import net.citizensnpcs.api.util.schedulers.adapter.NeoForgeScheduler;
import net.citizensnpcs.api.command.CommandManager;
import net.citizensnpcs.api.command.Injector;
import net.citizensnpcs.commands.CommandRegistry;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.commands.EditorCommands;
import net.citizensnpcs.commands.NPCCommands;
import net.citizensnpcs.commands.TraitCommands;
import net.citizensnpcs.commands.WaypointCommands;
import net.citizensnpcs.npc.CitizensNPC;
import net.citizensnpcs.npc.CitizensNPCRegistry;
import net.citizensnpcs.npc.CitizensTraitFactory;
import net.citizensnpcs.npc.ai.CitizensNavigator;
import net.citizensnpcs.trait.ChunkTicketTrait;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;

/**
 * Mod entry point, and the {@link CitizensPlugin} implementation the API resolves against.
 * <p>
 * Lifecycle mapping from the Bukkit plugin:
 * <ul>
 * <li>{@code onEnable} → {@link #onServerStarting} (registries, stores) and {@link #onServerStarted} (NPC loading,
 * once every level exists)
 * <li>{@code onDisable} → {@link #onServerStopping}
 * <li>{@code getDataFolder()} → {@code config/citizens}
 * </ul>
 */
@Mod(Citizens.MOD_ID)
public class Citizens implements CitizensPlugin {
    private final List<NPCRegistry> anonymousRegistries = new ArrayList<>();
    private Settings config;
    private final File dataFolder;
    private boolean enabled;
    private EventListen eventListen;
    private ExpressionRegistry expressionRegistry;
    private BehaviorRegistry behaviorRegistry;
    private StoredShops shops;
    private CitizensNPCRegistry npcRegistry;
    private boolean saveOnDisable = true;
    private NPCDataStore saves;
    private NeoForgeScheduler scheduler;
    private MinecraftServer server;
    private final net.citizensnpcs.api.npc.NPCSelector selector = new net.citizensnpcs.api.npc.SimpleNPCSelector();
    private final Map<String, NPCRegistry> storedRegistries = new HashMap<>();
    private NPCRegistry temporaryRegistry;
    private CitizensTraitFactory traitFactory;
    private final CommandManager commands = new CommandManager();
    private final CommandRegistry commandRegistry = new CommandRegistry(commands);
    private String version = "unknown";
    private TemplateRegistry templateRegistry;

    public Citizens(IEventBus modEventBus, ModContainer modContainer) {
        instance = this;
        version = modContainer.getModInfo().getVersion().toString();
        dataFolder = FMLPaths.CONFIGDIR.get().resolve(MOD_ID).toFile();
        NeoForge.EVENT_BUS.register(this);
        PermissionUtil.registerDefaults();
        for (String permission : List.of("citizens.ignore-owner", "citizens.npc.admin", "citizens.npc.remove.all",
                "citizens.npc.limit.0", "citizens.npc.command.ignoreerrors.globalnused",
                "citizens.npc.shop.editor.actions.edit-permission", "citizens.npc.shop.editor.actions.edit-condition")) {
            PermissionUtil.register(permission);
        }
        // RegisterCommandsEvent fires while the server is being constructed, before any of our start hooks, so the
        // command classes have to be scanned now or the Brigadier nodes would be built from an empty manager
        commands.setInjector(new Injector(this));
        commands.register(NPCCommands.class);
        commands.register(TraitCommands.class);
        commands.register(EditorCommands.class);
        commands.register(WaypointCommands.class);
        commands.register(AdminCommands.class);
        commands.register(TemplateCommands.class);
        // ChunkTicketTrait's controller has to be registered during startup, on the mod bus rather than the game bus
        modEventBus.addListener(RegisterTicketControllersEvent.class, event -> {
            event.register(ChunkTicketTrait.CONTROLLER);
            // the navigator holds its own ticket on the destination chunk while pathing there
            event.register(CitizensNavigator.CONTROLLER);
        });
        LOGGER.info("Citizens loading (mod container {})", modContainer.getModId());
    }

    @Override
    public NPCRegistry createAnonymousNPCRegistry(NPCDataStore store) {
        CitizensNPCRegistry anon = new CitizensNPCRegistry(store, this, "anonymous-" + UUID.randomUUID());
        anonymousRegistries.add(anon);
        return anon;
    }

    @Override
    public NPCRegistry createNamedNPCRegistry(String name, NPCDataStore store) {
        NPCRegistry created = new CitizensNPCRegistry(store, this, name);
        storedRegistries.put(name, created);
        return created;
    }

    private NPCDataStore createStorage(File folder) {
        Storage saves = new YamlStorage(new File(folder, "saves.yml"), "Citizens NPC Storage");
        if (!saves.load())
            return null;
        return SimpleNPCDataStore.create(saves);
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    /**
     * @return the loaded {@code config.yml}, or null before the server starts
     */
    public Settings getSettings() {
        return config;
    }

    @Override
    public NPCRegistry getNamedNPCRegistry(String name) {
        if (npcRegistry != null && name.equals(npcRegistry.getName()))
            return npcRegistry;
        return storedRegistries.get(name);
    }

    @Override
    public Iterable<NPCRegistry> getNPCRegistries() {
        return () -> Iterables
                .concat(Arrays.asList(npcRegistry), storedRegistries.values(), anonymousRegistries,
                        Arrays.asList(temporaryRegistry))
                .iterator();
    }

    @Override
    public net.citizensnpcs.api.npc.NPCSelector getDefaultNPCSelector() {
        return selector;
    }

    @Override
    public NPCRegistry getNPCRegistry() {
        return npcRegistry;
    }

    @Override
    public ClassLoader getOwningClassLoader() {
        return getClass().getClassLoader();
    }

    /** @return the store the default registry saves to, or null before the server starts */
    public NPCDataStore getDefaultNPCDataStore() {
        return saves;
    }

    public NeoForgeScheduler getScheduler() {
        return scheduler;
    }

    public MinecraftServer getServer() {
        return server;
    }

    @Override
    public NPCRegistry getTemporaryNPCRegistry() {
        return temporaryRegistry;
    }

    @Override
    public ExpressionRegistry getExpressionRegistry() {
        return expressionRegistry;
    }

    @Override
    public BehaviorRegistry getBehaviorRegistry() {
        return behaviorRegistry;
    }

    @Override
    public TemplateRegistry getTemplateRegistry() {
        return templateRegistry;
    }

    /**
     * Copies {@code templates/citizens/templates.yml} out of the jar on first run.
     * <p>
     * Upstream gets this for free from Bukkit's {@code saveResource}. There is no NeoForge equivalent, so the resource is
     * streamed out by hand; an existing file is left alone, so a user's edits survive every later start.
     */
    private void extractBundledTemplate() {
        File target = new File(dataFolder, "templates/citizens/templates.yml");
        if (target.exists())
            return;
        target.getParentFile().mkdirs();
        try (java.io.InputStream in = getClass().getClassLoader()
                .getResourceAsStream("templates/citizens/templates.yml")) {
            if (in == null) {
                Messaging.debug("No bundled templates.yml to extract");
                return;
            }
            java.nio.file.Files.copy(in, target.toPath());
            LOGGER.info("Extracted the example template to {}", target);
        } catch (java.io.IOException ex) {
            Messaging.severe("Could not extract the bundled templates.yml: " + ex.getMessage());
        }
    }

    public TraitFactory getTraitFactory() {
        return traitFactory;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void onImplementationChanged() {
        Messaging.severe("Citizens implementation changed, disabling");
        saveOnDisable = false;
    }

    /**
     * Loads stored NPCs. Deferred to server-started because {@code SimpleNPCDataStore.loadInto} resolves each NPC's
     * dimension, which is only possible once every level has been created.
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        net.citizensnpcs.util.YuuniverseEconomy.install();
        if (saves == null)
            return;
        try {
            saves.loadInto(npcRegistry);
            if (shops != null) {
                // after the NPCs, because a per-NPC shop is keyed by its NPC's UUID
                shops.load();
            }
            LOGGER.info("Loaded {} NPCs.", Iterables.size(npcRegistry));
        } catch (Throwable t) {
            Messaging.severe("Failed to load NPCs:", t.getMessage());
            t.printStackTrace();
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        scheduler = new NeoForgeScheduler(server);
        Schedulers.set(scheduler);

        dataFolder.mkdirs();
        config = new Settings(dataFolder);
        Translator.setInstance(dataFolder, Locale.getDefault());

        traitFactory = new CitizensTraitFactory();
        expressionRegistry = new ExpressionRegistry();
        expressionRegistry.registerEngine(new MolangEngine());
        behaviorRegistry = new CitizensBehaviorRegistry(expressionRegistry);
        try {
            // no JDK since 15 ships a JavaScript engine, so js`...` only works when a mod supplies one
            expressionRegistry.registerEngine(JSR223Engine.javascript());
        } catch (RuntimeException e) {
            Messaging.debug("No JavaScript engine found, js`...` expressions are unavailable");
        }
        CitizensAPI.setImplementation(this);

        // Group checks (shop requirements, player filters, guard targeting) resolve only if a permission mod is
        // present; whichever is installed wins, and with none Citizens answers "unknown" rather than guessing.
        // Managing groups is deliberately not Citizens' job - these are bridges to a real permission mod.
        if (!LuckPermsGroups.install()) {
            ParadigmGroups.install();
        }

        eventListen = new EventListen();
        NeoForge.EVENT_BUS.register(eventListen);
        // one dispatcher for every editor mode, rather than upstream's per-instance listener registration
        Editor.registerListeners();
        ChatPrompts.registerListeners();

        saves = createStorage(dataFolder);
        if (saves == null) {
            Messaging.severe("Could not load saves.yml, disabling");
            return;
        }
        npcRegistry = new CitizensNPCRegistry(saves, this);
        temporaryRegistry = new CitizensNPCRegistry(new MemoryNPCDataStore(), this, "temporary");

        shops = new StoredShops(new YamlStorage(new File(dataFolder, "shops.yml"), "Citizens NPC Shops"));
        if (!shops.loadFromDisk()) {
            Messaging.severe("Could not load shops.yml, NPC shops will not be available");
        }

        // The bundled example template ships in the jar under templates/citizens/; it is copied out on first run so the
        // folder a user is meant to edit is not empty, and never overwritten afterwards.
        extractBundledTemplate();
        templateRegistry = new TemplateRegistry(new File(dataFolder, "templates").toPath());

        // TODO(P7): command registration hooks in here
        enabled = true;
        LOGGER.info("Citizens enabled.");
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        server = null;
    }

    @SubscribeEvent
    public void onGatherPermissions(PermissionGatherEvent.Nodes event) {
        for (var type : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            PermissionUtil.register("citizens.npc.create." + type.getPath());
            PermissionUtil.register("citizens.npc.controllable." + type.getPath());
        }
        for (var node : PermissionUtil.getRegisteredNodes()) event.addNodes(node);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPermissionServicesReady(ServerStartedEvent event) {
        // Provider services need to finish starting before their API availability is checked.
        ParadigmPermissions.install();
        if (PermissionUtil.getGroupResolver() == null && !LuckPermsGroups.install()) ParadigmGroups.install();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        commandRegistry.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        enabled = false;
        ParadigmPermissions.uninstall();
        ParadigmGroups.uninstall();
        PermissionUtil.clearTemporary();
        net.citizensnpcs.util.YuuniverseEconomy.uninstall();
        Editor.leaveAll();
        ChatPrompts.abandonAll();
        if (npcRegistry != null) {
            if (saveOnDisable) {
                storeNPCs();
            }
            npcRegistry.despawnNPCs(DespawnReason.RELOAD);
        }
        if (temporaryRegistry != null) {
            temporaryRegistry.despawnNPCs(DespawnReason.RELOAD);
        }
        CitizensAPI.setImplementation(null);
        Schedulers.unset();
        if (eventListen != null) {
            NeoForge.EVENT_BUS.unregister(eventListen);
            eventListen = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        LOGGER.info("Citizens disabled.");
    }

    /**
     * Drives the per-tick NPC update.
     * <p>
     * Upstream relies on each NPC's entity being a Citizens subclass whose {@code customServerAiStep} calls
     * {@code npc.update()}. The port reuses vanilla entity classes, so there is no such hook and the registries are
     * walked here instead — one pass per tick, same ordering guarantees.
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (scheduler != null) {
            scheduler.tick();
        }
        if (!enabled)
            return;
        for (NPCRegistry registry : getNPCRegistries()) {
            if (registry == null) {
                continue;
            }
            for (Iterator<NPC> itr = registry.iterator(); itr.hasNext();) {
                NPC npc = itr.next();
                if (!(npc instanceof CitizensNPC) || !npc.isSpawned()) {
                    continue;
                }
                try {
                    ((CitizensNPC) npc).update();
                } catch (Throwable t) {
                    // one broken NPC must not stall the update pass for every other NPC
                    Messaging.severe("Error updating NPC", npc.getId() + ":", t.getMessage());
                    t.printStackTrace();
                }
            }
        }
    }

    @Override
    public void removeNamedNPCRegistry(String name) {
        storedRegistries.remove(name);
    }

    @Override
    public void setDefaultNPCDataStore(NPCDataStore store) {
        saves = store;
    }

    /** @return the mod version, for {@code /citizens} */
    public String getVersion() {
        return version;
    }

    /**
     * Re-reads config.yml, saves.yml and shops.yml from disk and respawns every NPC from what they now say.
     * <p>
     * Upstream additionally restarts its {@code PlayerUpdateTask} and resets {@code ProfileFetcher}; this port has
     * neither (skins are fetched through {@code MojangSkinGenerator} and cached in {@link Skin}, whose cache is cleared
     * here instead). Editors and chat prompts are abandoned first because both hold a reference to an NPC that is about
     * to be replaced by a freshly loaded one.
     */
    public void reload() throws NPCLoadException {
        Editor.leaveAll();
        ChatPrompts.abandonAll();
        config.reload();
        if (eventListen != null) {
            // the queued NPCs are about to be replaced by freshly loaded objects; a stale entry would respawn a copy
            eventListen.clearRespawnQueue();
        }
        if (npcRegistry != null) {
            npcRegistry.despawnNPCs(DespawnReason.RELOAD);
        }
        Skin.clearCache();
        if (saves != null) {
            saves.reloadFromSource();
            if (npcRegistry != null) {
                saves.loadInto(npcRegistry);
            }
        }
        if (shops != null) {
            shops.loadFromDisk();
            shops.load();
        }
        templateRegistry = new TemplateRegistry(new File(dataFolder, "templates").toPath());
        LOGGER.info("Citizens reloaded.");
    }

    public void storeNPCs() {
        if (saves == null || npcRegistry == null)
            return;
        saves.storeAll(npcRegistry);
        if (shops != null) {
            shops.storeShops();
            shops.saveToDiskImmediate();
        }
        saves.saveToDiskImmediate();
    }

    /**
     * @return every stored shop, both the per-NPC ones and the named global ones
     */
    public StoredShops getShops() {
        return shops;
    }

    public static Citizens getInstance() {
        return instance;
    }

    public static final Logger LOGGER = LoggerFactory.getLogger("Citizens");
    public static final String MOD_ID = "citizens";
    private static Citizens instance;
}
