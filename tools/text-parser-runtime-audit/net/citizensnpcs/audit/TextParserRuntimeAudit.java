package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.gui.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class TextParserRuntimeAudit {
    private static boolean done;
    private static int passed;
    private static ServerPlayer player;
    private static final List<EmbeddedChannel> channels = new ArrayList<>();
    private static final List<Component> chat = new ArrayList<>();
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done || event.getServer().getTickCount() < 10) return;
        done = true;
        var server = event.getServer();
        try {
            check(Files.isRegularFile(Path.of("text-parser-audit-fixture.txt")), "isolated_fixture");
            player = admit(server, "TextParserAudit");
            hover(); dynamic(); messaging(); consumers();
            LoggerFactory.getLogger("citizens").info("[TEXTPARSERAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[TEXTPARSERAUDIT] FAILED", failure);
        } finally {
            try {
                if (player != null) server.getPlayerList().remove(player);
                for (var channel : channels) channel.finishAndReleaseAll();
            } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[TEXTPARSERAUDIT] FAILED cleanup", failure); }
            server.halt(false);
        }
    }
    private static ServerPlayer admit(net.minecraft.server.MinecraftServer server, String name) {
        ServerPlayer who = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel admittedChannel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    connection.configurePacketHandler(channel.pipeline());
                    channel.pipeline().addLast("menu-capture", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                            if (message instanceof ClientboundSystemChatPacket packet) {
                                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
                                try {
                                    ClientboundSystemChatPacket.STREAM_CODEC.encode(buffer, packet);
                                    chat.add(ClientboundSystemChatPacket.STREAM_CODEC.decode(buffer).content());
                                } finally { buffer.release(); }
                            }
                            super.write(ctx, message, promise);
                        }
                    });
                }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(who.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, who, cookie);

        channels.add(admittedChannel);
        return who;
    }


    private static void hover() {
        var simple = parse("<hover:show_item:'minecraft:stone':3>Stone</hover>");
        var item = hoverOf(simple).getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_ITEM).getItemStack();
        check(item.is(Items.STONE) && item.getCount() == 3, "native_item_hover_type_and_quantity");
        var modern = parse("<hover:show_item:'minecraft:diamond_sword':2:'minecraft:custom_name':'\"Sword\"':'minecraft:damage':7>Item</hover>");
        item = hoverOf(modern).getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_ITEM).getItemStack();
        check(item.is(Items.DIAMOND_SWORD) && item.getCount() == 2 && item.getDamageValue() == 7
                && item.getHoverName().getString().equals("Sword"), "native_component_snbt_in_item_hover");
        var legacy = parse("<hover:show_item:'minecraft:diamond_sword':1:'{Damage:9,CustomModelData:7,Unbreakable:1b,Extra:\"keep\"}'>Legacy</hover>");
        item = hoverOf(legacy).getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_ITEM).getItemStack();
        check(item.getDamageValue() == 9 && item.get(DataComponents.CUSTOM_MODEL_DATA).value() == 7
                && item.has(DataComponents.UNBREAKABLE), "legacy_item_tag_migrates_through_native_datafixer");
        check(item.get(DataComponents.CUSTOM_DATA).copyTag().getString("Extra").equals("keep"), "legacy_unknown_item_data_is_retained");
        String entityId = player.getUUID().toString();
        var entity = parse("<hover:show_entity:'minecraft:player':" + entityId + ":'<red>Player'>Entity</hover>");
        var info = hoverOf(entity).getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_ENTITY);
        check(info.type == net.minecraft.world.entity.EntityType.PLAYER && info.id.equals(player.getUUID())
                && info.name.orElseThrow().getString().equals("Player"), "native_entity_hover_uuid_type_and_name");
        for (Component component : List.of(simple, modern, legacy, entity,
                parse("<hover:show_text:'<red>Tip > inside'>Body</hover>"))) {
            chat.clear(); player.sendSystemMessage(component); drain();
            check(!chat.isEmpty() && json(chat.getLast()).equals(json(component)), "chat_packet_codec_retains_native_hover");
        }
        for (String raw : List.of("<hover:show_item:'absent:missing'>Item</hover>",
                "<hover:show_entity:'absent:missing':" + entityId + ">Entity</hover>",
                "<hover:show_item:'minecraft:stone':1:'absent:component':3>Item</hover>")) {
            check(parse(raw).getString().equals(raw), "unavailable_payload_retains_original_text");
        }
        check(parse("<hover:show_item:'minecraft:stone':1:'{invalid'>Item</hover>").getString().contains("{invalid"),
                "malformed_item_payload_is_not_silently_dropped");
    }

    private static void dynamic() {
        var source = player.createCommandSourceStack();
        var privileged = source.withPermission(2);
        check(net.citizensnpcs.api.util.TextParser.parse("<selector:@a>", source.withPermission(0)).getString().equals("<selector:@a>"),
                "dynamic_resolution_does_not_elevate_source_permissions");
        check(net.citizensnpcs.api.util.TextParser.parse("<selector:@s>", privileged).getString().equals(player.getName().getString()),
                "selector_resolves_with_actual_source_entity");
        var board = player.getServer().getScoreboard();
        var objective = board.addObjective("parser_audit", net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY,
                Component.literal("Parser"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
        try {
            board.getOrCreatePlayerScore(player, objective).set(37);
            check(net.citizensnpcs.api.util.TextParser.parse("<score:TextParserAudit:parser_audit>", source).getString().equals("37"),
                    "score_resolves_authoritative_scoreboard");
            board.getOrCreatePlayerScore(player, objective).set(42);
            check(net.citizensnpcs.api.util.TextParser.parse("<score:TextParserAudit:parser_audit>", source).getString().equals("42"),
                    "score_reloads_current_value_without_shadow_state");
        } finally { board.removeObjective(objective); }
        var key = net.minecraft.resources.ResourceLocation.parse("citizens:parser_audit");
        var nbt = new net.minecraft.nbt.CompoundTag(); nbt.putString("value", "Stored value");
        player.getServer().getCommandStorage().set(key, nbt);
        try {
            check(net.citizensnpcs.api.util.TextParser.parse("<nbt:storage:'citizens:parser_audit':value>", privileged).getString().equals("Stored value"),
                    "nbt_storage_reads_native_command_storage");
        } finally { player.getServer().getCommandStorage().set(key, new net.minecraft.nbt.CompoundTag()); }
        for (String raw : List.of("<nbt:block:'~0 ~0 ~0':id>", "<nbt:entity:@s:Health>", "<nbt:storage:'citizens:parser_audit':value>")) {
            check(parse(raw).getContents() instanceof net.minecraft.network.chat.contents.NbtContents, "native_nbt_source_is_structured");
        }
        check(parse("<lang:chat.type.text:'<red>Name':'Message'>").getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents,
                "translation_keeps_client_localization_and_arguments");
        check(parse("<key:key.jump>").getContents() instanceof net.minecraft.network.chat.contents.KeybindContents,
                "keybind_keeps_client_key_binding");
    }

    private static void messaging() throws Exception {
        chat.clear();
        net.citizensnpcs.api.util.Messaging.sendColorless(player.createCommandSourceStack().withPermission(2), "<selector:@s>"); drain();
        check(chat.getLast().getString().equals(player.getName().getString()), "messaging_resolves_source_before_sending");
        for (Locale locale : List.of(Locale.ENGLISH, Locale.CHINESE)) {
            Path folder = Path.of("parser-translations", locale.getLanguage()); Files.createDirectories(folder);
            net.citizensnpcs.api.util.Translator.setInstance(folder.toFile(), locale);
            String prompt = net.citizensnpcs.api.util.Translator.translate("citizens.editors.text.start-prompt", "", "", "", "", "");
            chat.clear(); net.citizensnpcs.api.util.Messaging.sendColorless(player.createCommandSourceStack(), prompt); drain();
            String delivered = chat.stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
            check(delivered.contains(locale.equals(Locale.ENGLISH) ? "Add text" : "添加文本")
                    && delivered.contains(locale.equals(Locale.ENGLISH) ? "range" : "范围")
                    && !delivered.contains("</hover>") && !delivered.contains("<yellow>"), "localized_text_editor_body_" + locale);
            var tooltips = new ArrayList<String>();
            var commands = new HashSet<String>();
            for (Component line : chat) line.visit((style, text) -> {
                if (style.getClickEvent() != null) commands.add(style.getClickEvent().getValue());
                if (style.getHoverEvent() != null && style.getHoverEvent().getAction() == net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT)
                    tooltips.add(style.getHoverEvent().getValue(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT).getString());
                return Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);
            check(tooltips.stream().anyMatch(t -> t.contains(locale.equals(Locale.ENGLISH) ? "set to default to clear" : "设置为 默认 来清除")),
                    "localized_text_editor_nested_tooltip_" + locale);
            check(commands.containsAll(List.of("add ", "item ", "range ", "delay ")), "localized_editor_buttons_keep_command_values_" + locale);
        }
    }

    private static void consumers() {
        var npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().createNPC(net.minecraft.world.entity.EntityType.COW, "Parser NPC");
        try {
            npc.setName("<gradient:red:blue>😀NPC</gradient>");
            check(npc.getFullName().replaceAll("§.", "").equals("😀NPC"), "npc_name_retains_unicode_and_hides_markup");
            npc.setName("<#123456>Exact NPC");
            check(npc.getFullName().startsWith("§x§1§2§3§4§5§6"), "npc_legacy_name_projection_preserves_exact_rgb");
            // Registry-backed data is also safe through the ordinary component persister.
            var key = new net.citizensnpcs.api.util.MemoryDataKey().getRelative("component");
            var persister = new net.citizensnpcs.api.persistence.ComponentPersister();
            Component original = parse("<hover:show_item:'minecraft:diamond':4><insert:'a:b'>Saved</insert></hover>");
            persister.save(original, key);
            check(json(original).equals(json(persister.create(key))),
                    "native_component_persistence_retains_item_hover_and_insertion");
        } finally { npc.destroy(); }
    }

    private static com.google.gson.JsonElement json(Component component) {
        return com.google.gson.JsonParser.parseString(Component.Serializer.toJson(component, player.registryAccess()));
    }
    private static Component parse(String raw) { return net.citizensnpcs.api.util.TextParser.parse(raw); }
    private static net.minecraft.network.chat.HoverEvent hoverOf(Component component) {
        var result = new ArrayList<net.minecraft.network.chat.HoverEvent>();
        component.visit((style, text) -> { if (!text.isEmpty() && style.getHoverEvent() != null) result.add(style.getHoverEvent()); return Optional.empty(); }, net.minecraft.network.chat.Style.EMPTY);
        check(!result.isEmpty(), "hover_is_native_not_literal_markup"); return result.getFirst();
    }
    private static void drain() {
        for (var channel : channels) { channel.runPendingTasks(); Object output; while ((output = channel.readOutbound()) != null) ReferenceCountUtil.release(output); }
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[TEXTPARSERAUDIT] PASS {}", label);
    }
}
