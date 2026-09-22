package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

/** Actual mod accessors consume the migrated items; expected payloads come from an independent NBT reader. */
@EventBusSubscriber(modid = "interactions")
public final class SavedItemRuntimeAudit {
    private static boolean forced, done;
    private static int passed;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, -60, 1))) return;
        done = true;
        try {
            if (!Files.isRegularFile(Path.of("saved-item-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("Saved item audit needs its own empty fixture");
            run(server);
            LoggerFactory.getLogger("interactions").info("[SAVEDITEMAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { LoggerFactory.getLogger("interactions").error("[SAVEDITEMAUDIT] FAILED", failure); }
        finally { server.halt(false); }
    }

    private static void run(MinecraftServer server) throws Exception {
        ItemLibrary library = new ItemLibrary();
        Path database = Path.of("config/interactions/items.yml"); byte[] original = Files.readAllBytes(database);
        library.load(database.toFile(), server.registryAccess());
        var expected = JsonParser.parseString(Files.readString(Path.of("expected-payloads.json"))).getAsJsonObject();
        int songs = 0, packages = 0, cans = 0;
        for (var element : expected.getAsJsonArray("internal_items")) {
            JsonObject record = element.getAsJsonObject(); String id = record.get("id").getAsString();
            ItemStack stack = library.get(id, 1); JsonObject nbt = record.getAsJsonObject("nbt");
            if (nbt.has("NetMusicSongInfo")) {
                check(stack != null, "music_disc_available_" + id);
                var nativeType = Class.forName("com.github.tartaricacid.netmusic.item.ItemMusicCD");
                Object song = nativeType.getMethod("getSongInfo", ItemStack.class).invoke(null, stack);
                JsonObject value = nbt.getAsJsonObject("NetMusicSongInfo").getAsJsonObject("value");
                check(song != null && song.getClass().getField("songName").get(song).equals(string(value, "name"))
                        && song.getClass().getField("songUrl").get(song).equals(string(value, "url"))
                        && song.getClass().getField("songTime").getInt(song) == value.getAsJsonObject("time").get("value").getAsInt(),
                        "native_music_accessor_reads_exact_song_" + id);
                roundTrip(server, stack); songs++;
            } else if (nbt.has("jerrycanFluid")) {
                check(stack != null, "fuel_can_available");
                var wrapperType = Class.forName("mcinterface1211.WrapperItemStack");
                var constructor = wrapperType.getDeclaredConstructor(ItemStack.class); constructor.setAccessible(true);
                Object wrapper = constructor.newInstance(stack); Object data = wrapperType.getMethod("getData").invoke(wrapper);
                check(data != null && Class.forName("minecrafttransportsimulator.mcinterface.IWrapperNBT")
                        .getMethod("getString", String.class).invoke(data, "jerrycanFluid").equals(string(nbt, "jerrycanFluid")),
                        "native_vehicle_wrapper_reads_exact_fuel");
                roundTrip(server, stack); cans++;
            } else if (nbt.has("Items")) {
                String childId = nbt.getAsJsonObject("Items").getAsJsonObject("value").getAsJsonArray("values")
                        .get(0).getAsJsonObject().getAsJsonObject("id").get("value").getAsString();
                String alias = ItemAliases.lookup(childId.replace(':', '_'));
                var childKey = net.minecraft.resources.ResourceLocation.parse(alias == null ? childId : alias);
                if (BuiltInRegistries.ITEM.getOptional(childKey).isEmpty()) {
                    check(stack == null, "package_with_unavailable_child_is_not_empty_reward_" + id); continue;
                }
                check(stack != null, "package_available_" + id);
                var packageType = Class.forName("com.mrcrayfish.furniture.refurbished.item.PackageItem");
                var contents = (ItemContainerContents) packageType.getMethod("getPackagedItems", ItemStack.class).invoke(null, stack);
                ItemStack child = contents.stream().filter(item -> !item.isEmpty()).findFirst().orElseThrow();
                int count = nbt.getAsJsonObject("Items").getAsJsonObject("value").getAsJsonArray("values")
                        .get(0).getAsJsonObject().getAsJsonObject("Count").get("value").getAsInt();
                check(BuiltInRegistries.ITEM.getKey(child.getItem()).equals(childKey) && child.getCount() == count,
                        "native_package_accessor_reads_renamed_child_and_count_" + id);
                var componentType = BuiltInRegistries.DATA_COMPONENT_TYPE.get(net.minecraft.resources.ResourceLocation.parse("refurbished_furniture:package_info"));
                Object info = stack.get(componentType);
                check(info.getClass().getMethod("sender").invoke(info).equals(java.util.Optional.of(string(nbt, "Sender")))
                        && info.getClass().getMethod("message").invoke(info).equals(java.util.Optional.of(string(nbt, "Message"))),
                        "native_package_info_preserves_sender_and_message_" + id);
                if (id.equals("161")) check(child.get(DataComponents.LORE).lines().size() == 2
                        && child.get(DataComponents.LORE).lines().get(1).getString().contains("100 UDT"), "nested_legacy_lore_upgrades_to_components");
                roundTrip(server, stack); packages++;
            }
        }
        check(songs == 9 && packages == 2 && cans == 1, "all_available_original_internal_items_verified");
        check(java.util.Arrays.equals(original, Files.readAllBytes(database)), "source_database_unchanged");
        packageShapes(server);
        failurePayment(server, library);
    }

    private static void packageShapes(MinecraftServer server) {
        var packageId = net.minecraft.resources.ResourceLocation.parse("refurbished_furniture:package");
        var tag = new net.minecraft.nbt.CompoundTag(); var children = new net.minecraft.nbt.ListTag();
        children.add(child("minecraft:gold_ingot", 2, 0)); children.add(child("minecraft:paper", 3, 5));
        tag.put("Items", children); tag.putString("Sender", "Sender"); tag.putString("Message", "Message"); tag.putString("unknown-provider", "preserved");
        var before = tag.copy();
        ItemStack stack = LegacyItemData.convert(packageId, tag, 3465, server.registryAccess());
        var contents = stack.get(DataComponents.CONTAINER);
        check(contents.getSlots() == 6 && contents.getStackInSlot(0).is(Items.GOLD_INGOT)
                && contents.getStackInSlot(0).getCount() == 2 && contents.getStackInSlot(3).isEmpty()
                && contents.getStackInSlot(5).is(Items.PAPER) && contents.getStackInSlot(5).getCount() == 3,
                "sparse_multiple_package_slots_are_preserved");
        check(tag.equals(before) && stack.get(DataComponents.CUSTOM_DATA).copyTag().getString("unknown-provider").equals("preserved"),
                "conversion_preserves_source_and_unhandled_provider_data");
        roundTrip(server, stack);
        var nested = child(packageId.toString(), 1, 2); nested.put("tag", tag.copy());
        var nestedItems = new net.minecraft.nbt.ListTag(); nestedItems.add(nested);
        var outer = new net.minecraft.nbt.CompoundTag(); outer.put("Items", nestedItems);
        ItemStack upgraded = LegacyItemData.convert(packageId, outer, 3465, server.registryAccess());
        check(upgraded.get(DataComponents.CONTAINER).getStackInSlot(2).get(DataComponents.CONTAINER).equals(contents),
                "nested_mod_package_uses_same_component_conversion");
        roundTrip(server, upgraded);
        for (String failure : List.of("duplicate", "unavailable", "wrong-list", "wrong-slot", "zero-count", "wrong-info")) {
            var invalid = tag.copy(); var invalidChildren = (net.minecraft.nbt.ListTag) invalid.get("Items");
            switch (failure) {
                case "duplicate" -> invalidChildren.add(child("minecraft:diamond", 1, 0));
                case "unavailable" -> invalidChildren.add(child("unavailable:nested_item", 1, 1));
                case "wrong-list" -> { var wrong = new net.minecraft.nbt.ListTag(); wrong.add(net.minecraft.nbt.StringTag.valueOf("bad")); invalid.put("Items", wrong); }
                case "wrong-slot" -> invalidChildren.getCompound(0).putString("Slot", "0");
                case "zero-count" -> invalidChildren.getCompound(0).putByte("Count", (byte) 0);
                case "wrong-info" -> invalid.putInt("Sender", 1);
            }
            boolean rejected = false;
            try { LegacyItemData.convert(packageId, invalid, 3465, server.registryAccess()); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "invalid_package_is_not_partially_decoded_" + failure);
        }
    }

    private static net.minecraft.nbt.CompoundTag child(String id, int count, int slot) {
        var result = new net.minecraft.nbt.CompoundTag(); result.putString("id", id);
        result.putByte("Count", (byte) count); result.putByte("Slot", (byte) slot); return result;
    }

    private static void failurePayment(MinecraftServer server, ItemLibrary library) {
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "ItemAudit"), ClientInformation.createDefault());
        player.setPos(1, -60, 1);
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        var actions = new Actions(library, new Economy());
        try {
            player.getInventory().clearContent(); player.getInventory().add(new ItemStack(Items.PAPER));
            check(!actions.runAll(List.of("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%",
                    "console_command: si give 163 1 ItemAudit silent"), player, Component.literal("Items"))
                    && player.getInventory().countItem(Items.PAPER) == 1, "unavailable_nested_provider_rejects_before_payment");
            check(actions.runAll(List.of("remove_item: %checkitem_remove_mat:minecraft:paper,amt:1%",
                    "console_command: si give 1001 2 ItemAudit silent"), player, Component.literal("Items"))
                    && player.getInventory().countItem(Items.PAPER) == 0, "valid_saved_disc_reward_executes_after_payment");
            ItemStack disc = library.get("1001", 2);
            check(player.getInventory().items.stream().anyMatch(stack -> ItemStack.matches(stack, disc)),
                    "saved_item_reward_keeps_provider_components_and_count");
            ItemStack parcel = library.get("161", 1);
            ItemStack contents = parcel.get(DataComponents.CONTAINER).stream().filter(stack -> !stack.isEmpty()).findFirst().orElseThrow().copy();
            player.getInventory().clearContent(); player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, parcel);
            parcel.use(server.overworld(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
            List<net.minecraft.world.entity.item.ItemEntity> dropped = server.overworld().getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(3));
            check(player.getMainHandItem().isEmpty() && dropped.stream().anyMatch(entity -> ItemStack.matches(entity.getItem(), contents)),
                    "actual_package_use_releases_exact_migrated_contents");
            check(library.get("161", 1).get(DataComponents.CONTAINER).stream().anyMatch(stack -> ItemStack.matches(stack, contents)),
                    "opening_reward_does_not_mutate_saved_template");
        } finally { actions.reset(true); server.getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }

    private static String string(JsonObject object, String field) { return object.getAsJsonObject(field).get("value").getAsString(); }

    private static void roundTrip(MinecraftServer server, ItemStack stack) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess(), ConnectionType.NEOFORGE);
        try {
            ItemStack.STREAM_CODEC.encode(buffer, stack); ItemStack decoded = ItemStack.STREAM_CODEC.decode(buffer);
            check(ItemStack.matches(stack, decoded) && !buffer.isReadable(), "item_components_survive_actual_network_codec");
            check(ItemStack.matches(stack, ItemStack.parse(server.registryAccess(), stack.save(server.registryAccess())).orElseThrow()),
                    "item_components_survive_native_save_load");
        } finally { buffer.release(); }
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("interactions").info("[SAVEDITEMAUDIT] PASS {}", name);
    }
}
