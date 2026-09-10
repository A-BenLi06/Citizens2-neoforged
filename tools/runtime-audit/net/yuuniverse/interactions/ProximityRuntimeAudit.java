package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.util.Location;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class ProximityRuntimeAudit {
    private static boolean ran;
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (ran || event.getServer().getTickCount() < 45) return;
        ran = true;
        var registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        var npc = registry.createNPC(EntityType.PIG, "ProximityAudit");
        InteractionsMod controller = new InteractionsMod();
        NeoForge.EVENT_BUS.unregister(controller);
        try {
            npc.spawn(new Location(event.getServer().overworld(), 0, -60, 0));
            var player = new FakePlayer(event.getServer().overworld(), new GameProfile(UUID.randomUUID(), "EntryAudit"));
            var folder = Files.createTempDirectory("citizens-proximity-audit");
            Files.writeString(folder.resolve("entry.yml"), """
                    starts_with:
                      - NPC named ProximityAudit
                    start_conversation_radius: 3
                    end_conversation_radius: 5
                    can_be_started_on_air: true
                    conversation:
                      conversation1:
                        dialogue:
                          dialogue1:
                            text: [Audit]
                            time: 100
                    """);
            ConversationLibrary library = (ConversationLibrary) field(controller, "library");
            library.load(folder.toFile());
            var actionsField = InteractionsMod.class.getDeclaredField("actions");
            actionsField.setAccessible(true);
            actionsField.set(controller, new Actions(new ItemLibrary(), new Economy()));
            @SuppressWarnings("unchecked")
            Map<UUID, Session> sessions = (Map<UUID, Session>) field(controller, "sessions");
            Conversation story = library.forNpcName("ProximityAudit");
            check(story != null && story.first() != null, "loaded_named_conversation");
            player.setPos(4, -60, 0);
            controller.pollProximity(player, registry);
            check(sessions.isEmpty(), "outside_radius_does_not_start");
            player.setPos(3, -60, 0);
            controller.pollProximity(player, registry);
            check(sessions.containsKey(player.getUUID()), "entry_starts_named_conversation");
            Session original = sessions.get(player.getUUID());
            var chat = new net.neoforged.neoforge.event.ServerChatEvent(player, "hello",
                    net.minecraft.network.chat.Component.literal("hello"));
            controller.onChat(chat);
            check(chat.isCanceled(), "default_blocks_ordinary_chat");
            var settingsField = InteractionsMod.class.getDeclaredField("settings");
            settingsField.setAccessible(true);
            settingsField.set(controller, new DialogueSettings(true, false));
            chat = new net.neoforged.neoforge.event.ServerChatEvent(player, "hello",
                    net.minecraft.network.chat.Component.literal("hello"));
            controller.onChat(chat);
            check(!chat.isCanceled(), "configured_chat_is_allowed");
            var zombie = EntityType.ZOMBIE.create(player.serverLevel());
            NeoForge.EVENT_BUS.register(controller);
            try {
                zombie.setTarget(player);
                check(zombie.getTarget() == null, "active_dialogue_prevents_mob_target");
                settingsField.set(controller, new DialogueSettings(true, true));
                zombie.setTarget(player);
                check(zombie.getTarget() == player, "configured_mob_target_is_allowed");
                zombie.setTarget(null);
            } finally {
                NeoForge.EVENT_BUS.unregister(controller);
                settingsField.set(controller, DialogueSettings.DEFAULT);
            }
            controller.pollProximity(player, registry);
            check(sessions.get(player.getUUID()) == original, "active_session_not_replaced");
            original.end(false);
            controller.onServerTick(event);
            controller.pollProximity(player, registry);
            check(sessions.isEmpty(), "staying_inside_does_not_restart");
            player.setPos(4, -60, 0);
            controller.pollProximity(player, registry);
            player.setPos(2, -60, 0);
            controller.pollProximity(player, registry);
            check(sessions.containsKey(player.getUUID()), "leaving_and_reentering_restarts");
            sessions.remove(player.getUUID()).end(false);
            player.setPos(4, -60, 0);
            controller.pollProximity(player, registry);
            story.requiresPermission = true;
            player.setPos(2, -60, 0);
            controller.pollProximity(player, registry);
            check(sessions.isEmpty(), "permission_required_blocks_entry");
            story.requiresPermission = false;
            player.setPos(4, -60, 0);
            controller.pollProximity(player, registry);
            story.canBeStartedOnAir = false;
            player.setPos(0, -58, 0);
            controller.pollProximity(player, registry);
            check(sessions.isEmpty(), "air_start_disabled_blocks_entry");
            Files.delete(folder.resolve("entry.yml"));
            Files.delete(folder);
            LoggerFactory.getLogger("interactions").info("[PROXIMITYAUDIT] COMPLETE 12/12");
        } catch (Throwable failure) {
            LoggerFactory.getLogger("interactions").error("[PROXIMITYAUDIT] FAILED", failure);
        } finally {
            npc.destroy();
        }
    }
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        LoggerFactory.getLogger("interactions").info("[PROXIMITYAUDIT] PASS {}", name);
    }
}
