package net.yuuniverse.interactions;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NPC dialogue, reading the files the Bukkit Interactions plugin wrote.
 * <p>
 * A separate mod from Citizens on purpose. It drives Citizens NPCs and depends on them - the old plugin did exactly the
 * same - but dialogue is not an NPC framework's job, and this way it can be updated or removed on its own.
 * <p>
 * Everything lives under {@code config/interactions/}: {@code conversations/} holds the files copied from the old server
 * untouched, {@code players/} the per-player progress in the same format, and {@code items.yml} the saved-item database
 * the {@code si give} actions hand out.
 */
@Mod(InteractionsMod.MOD_ID)
public class InteractionsMod implements Session.Engine {
    public static final String MOD_ID = "interactions";
    private static final Logger LOGGER = LoggerFactory.getLogger("interactions");

    private final File dataFolder = FMLPaths.CONFIGDIR.get().resolve(MOD_ID).toFile();
    private final ConversationLibrary library = new ConversationLibrary();
    private final ItemLibrary items = new ItemLibrary();
    private final Economy economy = new Economy();
    private final ProgressStore progress = new ProgressStore(new File(dataFolder, "players"));
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> proximityEntries = new HashMap<>();
    private Actions actions;
    private int saveCountdown = 600;

    public InteractionsMod() {
        NeoForge.EVENT_BUS.register(this);
    }

    @Override
    public Actions actions() {
        return actions;
    }

    @Override
    public ProgressStore progress() {
        return progress;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        dataFolder.mkdirs();
        new File(dataFolder, "conversations").mkdirs();
        new File(dataFolder, "players").mkdirs();
        economy.install();
        ItemAliases.load(new File(dataFolder, "item-aliases.yml"));
        CommandAliases.load(new File(dataFolder, "command-aliases.yml"));
        items.load(new File(dataFolder, "items.yml"), event.getServer().registryAccess());
        actions = new Actions(items, economy);
        library.load(new File(dataFolder, "conversations"));
        progress.loadAll();
        if (library.size() == 0) {
            LOGGER.warn("No conversations loaded. Copy the old server's Interactions conversations/*.yml into {}.",
                    new File(dataFolder, "conversations"));
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        for (Session session : sessions.values()) {
            session.end(false);
        }
        sessions.clear();
        proximityEntries.clear();
        progress.saveDirty();
    }

    /** Right-clicking an NPC starts its conversation, which is what {@code conversation_start_click_type} said. */
    @SubscribeEvent
    public void onRightClick(NPCRightClickEvent event) {
        NPC npc = event.getNPC();
        ServerPlayer player = event.getClicker();
        if (npc == null || player == null || actions == null)
            return;
        Conversation conversation = library.forNpc(npc.getId());
        if (conversation == null) {
            // 15 of the migrated files trigger on the NPC's name rather than its id
            conversation = library.forNpcName(npc.getName());
        }
        if (conversation == null)
            return;
        startConversation(player, npc, conversation);
    }

    private void startConversation(ServerPlayer player, NPC npc, Conversation conversation) {
        if (sessions.containsKey(player.getUUID()))
            return;
        if (!npc.isSpawned() || npc.getEntity().level() != player.level()
                || conversation.isOutsideEndRadius(npc.getEntity().distanceToSqr(player)))
            return;
        if (conversation.requiresPermission && !net.citizensnpcs.api.util.PermissionUtil.hasPermission(
                player, "interactions.start." + conversation.id()))
            return;
        if (!conversation.canBeStartedOnAir && !player.level().getBlockState(
                net.minecraft.core.BlockPos.containing(player.getX(), player.getY() - 1, player.getZ())).isSolid())
            return;
        if (!progress.isReadable(player.getUUID())
                || progress.isCoolingDown(player.getUUID(), conversation.id(), conversation.cooldownSeconds,
                        System.currentTimeMillis())) {
            return;
        }
        Conversation.Node first = conversation.first();
        if (first == null)
            return;
        if (conversation.cooldownSeconds > 0) {
            progress.setCooldown(player.getUUID(), player.getGameProfile().getName(), conversation.id(),
                    System.currentTimeMillis());
            progress.saveDirty();
        }
        sessions.put(player.getUUID(), new Session(this, conversation, first, player, npc.getEntity()));
    }

    void pollProximity(ServerPlayer player, Iterable<NPC> npcs) {
        if (sessions.containsKey(player.getUUID())) return;
        NPC nearest = null;
        Conversation selected = null;
        double closest = Double.POSITIVE_INFINITY;
        for (NPC npc : npcs) {
            if (!npc.isSpawned() || npc.getEntity().level() != player.level()) continue;
            Conversation candidate = library.forNpc(npc.getId());
            if (candidate == null) candidate = library.forNpcName(npc.getName());
            if (candidate == null) continue;
            double distance = npc.getEntity().distanceToSqr(player);
            if (candidate.isWithinStartRadius(distance) && distance < closest) {
                nearest = npc;
                selected = candidate;
                closest = distance;
            }
        }
        if (selected == null) {
            proximityEntries.remove(player.getUUID());
        } else if (!selected.id().equals(proximityEntries.put(player.getUUID(), selected.id()))) {
            startConversation(player, nearest, selected);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (actions != null && event.getServer().getTickCount() % 20 == 0) {
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                pollProximity(player, CitizensAPI.getNPCRegistry());
            }
        }
        if (!sessions.isEmpty()) {
            sessions.values().removeIf(session -> {
                try {
                    session.tick();
                } catch (Exception ex) {
                    LOGGER.error("Dialogue tick failed for {}: {}", session.player().getGameProfile().getName(),
                            ex.toString());
                    session.end(false);
                }
                return session.isFinished();
            });
        }
        if (--saveCountdown <= 0) {
            saveCountdown = 600;
            progress.saveDirty();
        }
    }

    /**
     * Takes the player's chat while a conversation is running.
     * <p>
     * This is the half the click-only version was missing: a player answers a prompt by typing, and without this their
     * answer goes out as a public chat message while the dialogue keeps waiting. The old plugin suppressed chat outright
     * during a conversation ({@code allow_chat_while_in_conversation: false}), so taking the message is also what that
     * server behaved like.
     */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        Session session = sessions.get(event.getPlayer().getUUID());
        if (session == null || session.isFinished())
            return;
        String typed = event.getRawText().trim();
        if (!session.isAwaitingChoice()) {
            // mid-line: the message is still swallowed rather than shouted, but there is nothing to answer yet
            event.setCanceled(true);
            return;
        }
        if (session.chooseByText(typed)) {
            event.setCanceled(true);
            return;
        }
        event.setCanceled(true);
        event.getPlayer().sendSystemMessage(Component.literal(" 请输入 1-" + session.offeredCount()
                + " 之间的编号,或点击上面的选项。").withStyle(net.minecraft.ChatFormatting.YELLOW));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        proximityEntries.remove(event.getEntity().getUUID());
        Session session = sessions.remove(event.getEntity().getUUID());
        if (session != null) {
            session.end(false);
        }
        progress.saveDirty();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("interactions");
        root.then(Commands.literal("choose").then(Commands.argument("option", IntegerArgumentType.integer(1))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    Session session = player == null ? null : sessions.get(player.getUUID());
                    if (session == null || !session.choose(IntegerArgumentType.getInteger(context, "option"))) {
                        return 0;
                    }
                    return 1;
                })));
        root.then(Commands.literal("reload").requires(source -> source.hasPermission(3)).executes(context -> {
            MinecraftServer server = context.getSource().getServer();
            for (Session session : sessions.values()) {
                session.end(false);
            }
            sessions.clear();
            proximityEntries.clear();
            progress.saveDirty();
            ItemAliases.load(new File(dataFolder, "item-aliases.yml"));
            CommandAliases.load(new File(dataFolder, "command-aliases.yml"));
            items.load(new File(dataFolder, "items.yml"), server.registryAccess());
            library.load(new File(dataFolder, "conversations"));
            progress.loadAll();
            context.getSource().sendSuccess(() -> Component.literal("Interactions reloaded: " + library.size()
                    + " conversation(s) on " + library.npcCount() + " NPC(s), " + items.size() + " saved item(s)"),
                    true);
            return 1;
        }));
        root.then(Commands.literal("status").executes(context -> {
            context.getSource().sendSuccess(() -> Component.literal("Interactions: " + library.size()
                    + " conversation(s) on " + library.npcCount() + " NPC(s), " + items.size() + " saved item(s), "
                    + sessions.size() + " in progress"), false);
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    /** @return whether Citizens can be reached, so a broken install says so rather than doing nothing */
    static boolean citizensReady() {
        try {
            return CitizensAPI.hasImplementation();
        } catch (Throwable ex) {
            return false;
        }
    }
}
