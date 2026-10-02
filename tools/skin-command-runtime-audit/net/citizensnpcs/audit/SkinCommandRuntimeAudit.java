package net.citizensnpcs.audit;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import com.sun.net.httpserver.HttpServer;
import com.google.common.cache.LoadingCache;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.npc.skin.Skin;
import net.citizensnpcs.trait.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class SkinCommandRuntimeAudit {
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCRegistry registry;
    private static Actor alice;
    private static CommandSourceStack source;
    private static final List<NPC> npcs = new ArrayList<>();
    private static PermissionUtil.Attachment grant;
    private static HttpServer http;
    private static ExecutorService httpPool;
    private static ProxySelector originalProxy;
    private static final Queue<Request> requests = new ConcurrentLinkedQueue<>();
    private static final Set<Request> inFlight = ConcurrentHashMap.newKeySet();
    private static final Queue<Step> steps = new ArrayDeque<>();
    private static BooleanSupplier waiting = () -> true;
    private static int passed, deadline, sequence, stepNumber;
    private static boolean done, forced, initialized;
    private static String endpoint;
    private static byte[] png;
    private static Path skins;
    private static NPC current;
    private static SkinTrait trait;
    private static String expected;
    private static int messageCount;
    private static CompletableFuture<Optional<GameProfile>> delayed;
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private record Reply(int status, byte[] bytes, Map<String, String> headers) { }
    private record Request(URI uri, byte[] bytes, Map<String, List<String>> headers, CompletableFuture<Reply> reply) { }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("skin-command-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_registry");
                level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 2400;
            }
            if (server.getTickCount() > deadline) throw new AssertionError("timeout_step_" + stepNumber);
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (!initialized) { initialized = true; setup(); }
            alice.pump();
            if (!waiting.getAsBoolean()) return;
            waiting = () -> true;
            if (!steps.isEmpty()) { stepNumber++; steps.remove().run(); return; }
            check(requests.isEmpty(), "all_service_requests_consumed");
            close(); LoggerFactory.getLogger("citizens").info("[SKINCOMMANDAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SKINCOMMANDAUDIT] FAILED step=" + stepNumber, failure); close();
        }
    }

    private static void setup() throws Exception {
        registry = CitizensAPI.getNPCRegistry(); alice = actor("SkinCommandAlice"); source = alice.player.createCommandSourceStack();
        grant = PermissionUtil.grantTemporary(alice.player, List.of("citizens.npc.skin"));
        Skin.clearCache();
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpPool = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "Skin audit HTTP"); thread.setDaemon(true); return thread; });
        http.setExecutor(httpPool);
        http.createContext("/", exchange -> {
            Request request = new Request(exchange.getRequestURI(), exchange.getRequestBody().readAllBytes(), exchange.getRequestHeaders(), new CompletableFuture<>());
            inFlight.add(request); requests.add(request);
            try {
                Reply reply = request.reply.get(30, TimeUnit.SECONDS);
                reply.headers.forEach((key, value) -> exchange.getResponseHeaders().add(key, value));
                exchange.sendResponseHeaders(reply.status, reply.bytes.length);
                exchange.getResponseBody().write(reply.bytes);
            } catch (Exception failure) { if (!done) LoggerFactory.getLogger("citizens").error("[SKINCOMMANDAUDIT] FAILED HTTP", failure); }
            finally { inFlight.remove(request); exchange.close(); }
        });
        http.start(); endpoint = "http://127.0.0.1:" + http.getAddress().getPort();
        originalProxy = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return "textures.minecraft.net".equals(uri.getHost()) ? List.of(new Proxy(Proxy.Type.HTTP, http.getAddress())) : originalProxy.select(uri);
            }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { originalProxy.connectFailed(uri, address, failure); }
        });
        Setting.SKIN_GENERATION_ENDPOINT.set(endpoint + "/v2/generate");
        Setting.SKIN_GENERATION_API_KEY.set("fixture-key");
        Setting.BEDROCK_SKIN_ENDPOINT.set(endpoint + "/v2/");
        skins = CitizensAPI.getDataFolder().toPath().resolve("skins"); Files.createDirectories(skins.resolve("nested"));
        var image = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(76123);
        for (int x=0; x<64; x++) for (int y=0; y<64; y++) image.setRGB(x,y,random.nextInt());
        ByteArrayOutputStream output = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"PNG",output); png = output.toByteArray();
        check(png.length > 10000, "export_fixture_exceeds_old_truncation");
        Files.write(skins.resolve("input.png"), png);
        commands(); imports(); nameLookups(); exports(); bedrock();
    }

    private static void commands() {
        steps.add(() -> {
            current = npc(false, false); current.removeTrait(SkinTrait.class); select(current);
            reject("skin -t name broken signature"); reject("skin --file ../escape.png"); reject("skin --url file:///tmp/skin.png"); reject("skin --unknown yes");
            check(!current.hasTrait(SkinTrait.class), "invalid_skin_does_not_attach_trait");
            run("skin"); check(!current.hasTrait(SkinTrait.class), "read_only_query_does_not_attach_trait");
            expected = texture("direct"); run("skin -t Direct " + expected + " signature"); trait = current.getTraitNullable(SkinTrait.class);
            check(expected.equals(trait.getTexture()) && !trait.shouldUpdateSkins(), "direct_texture_and_persistent_mode");
            String oldName = trait.getSkinName(); reject("skin -t Changed " + Base64.getEncoder().encodeToString("{\"textures\":42}".getBytes(StandardCharsets.UTF_8)) + " signature");
            check(expected.equals(trait.getTexture()) && oldName.equals(trait.getSkinName()), "invalid_direct_texture_is_atomic");
            trait.setFetchDefaultSkin(false); check(current.spawn(new Location(level,4,-58,4)), "spawn_direct_skin");
            check(profileTexture((ServerPlayer) current.getEntity()).equals(expected), "direct_native_profile");
            run("skin -u"); check(alice.player.messages.stream().anyMatch(v -> v.contains("textures.minecraft.net/texture/direct")), "texture_url_output");
            run("skin -s");
            ItemStack head = alice.player.getInventory().items.stream().filter(s -> s.is(Items.PLAYER_HEAD)).findFirst().orElseThrow();
            check(head.get(DataComponents.PROFILE).gameProfile().getProperties().get("textures").iterator().next().value().equals(expected), "skull_uses_native_profile_component");
            run("skin -c");
            check(trait.getTexture() == null && trait.getSkinName() == null && profileTexture((ServerPlayer)current.getEntity()).isEmpty(), "clear_updates_live_profile");
            check(!current.data().has(Skin.CACHED_SKIN_UUID_NAME_METADATA), "clear_removes_cached_name");
            current.destroy();
            NPC cow = registry.createNPC(EntityType.COW,"WrongType"); npcs.add(cow); cow.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID()); select(cow);
            reject("skin Notch"); check(!cow.hasTrait(SkinTrait.class), "nonplayer_rejected"); cow.destroy();
        });
        for (boolean virtual : new boolean[] { false, true }) {
            steps.add(() -> {
                current=npc(virtual,true); select(current); trait=current.getTraitNullable(SkinTrait.class);
                expected=texture("skull-"+virtual); run("skin -t Source "+expected+" signature");
                for(int i=0;i<36;i++) alice.player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
                Set<Entity> before = entities(); run("skin -s");
                ItemEntity dropped=entities().stream().filter(e->!before.contains(e)&&e instanceof ItemEntity).map(e->(ItemEntity)e).findFirst().orElseThrow();
                check(dropped.getItem().get(DataComponents.PROFILE).gameProfile().getProperties().get("textures").iterator().next().value().equals(expected), "full_inventory_drops_profile_head_"+virtual);
                dropped.discard(); alice.player.getInventory().clearContent(); current.destroy();
            });
        }
    }

    private static void imports() {
        for(boolean virtual:new boolean[]{false,true}) {
            steps.add(() -> { current=npc(virtual,true); select(current); trait=current.getTraitNullable(SkinTrait.class); run("skin --url \"https://example.com/skin.png?name=é&x=1\" -s"); awaitRequest(); });
            steps.add(() -> {
                Request request=requests.remove(); JsonObject body=JsonParser.parseString(new String(request.bytes,StandardCharsets.UTF_8)).getAsJsonObject();
                check(body.get("url").getAsString().equals("https://example.com/skin.png?name=é&x=1") && body.get("variant").getAsString().equals("slim"), "url_utf8_json_and_slim");
                check(request.headers.get("Authorization").equals(List.of("Bearer fixture-key")), "configured_api_key_header");
                expected=texture("url-"+virtual); replyGenerated(request,expected); waiting=()->expected.equals(trait.getTexture());
            });
            steps.add(() -> {
                check(profileTexture((ServerPlayer)current.getEntity()).equals(expected),"url_applies_live_profile_"+virtual);
                MemoryDataKey saved=new MemoryDataKey(); current.save(saved); check(saved.getString("traits.skintrait.textureRaw").equals(expected),"import_persists_texture");
                run("skin --file input.png -s"); awaitRequest();
            });
            steps.add(() -> {
                Request request=requests.remove(); String body=new String(request.bytes,StandardCharsets.ISO_8859_1);
                check(body.contains("name=\"variant\"\r\n\r\nslim") && body.contains("filename=\"skin.png\"")
                        && body.contains(new String(png,StandardCharsets.ISO_8859_1)),"multipart_upload_retains_png_and_variant");
                expected=texture("file-"+virtual); replyGenerated(request,expected); waiting=()->expected.equals(trait.getTexture());
            });
            steps.add(() -> { check(profileTexture((ServerPlayer)current.getEntity()).equals(expected),"file_applies_live_profile_"+virtual); current.destroy(); });
        }
        for(String superseding:List.of("direct","clear","trait","permission","destroy")) {
            steps.add(() -> { current=npc(false,true); select(current); trait=current.getTraitNullable(SkinTrait.class); expected=texture("baseline"); trait.setSkinPersistent("Before","signature",expected); run("skin --url https://example.com/delayed.png"); awaitRequest(); });
            steps.add(() -> {
                switch(superseding) {
                    case "direct" -> { expected=texture("newer"); run("skin -t Newer "+expected+" signature"); }
                    case "clear" -> { run("skin -c"); expected=null; }
                    case "trait" -> { SkinTrait replacement=new SkinTrait(); replacement.setFetchDefaultSkin(false); current.addTrait(replacement); }
                    case "permission" -> grant.remove();
                    case "destroy" -> current.destroy();
                }
                messageCount=alice.player.messages.size(); replyGenerated(requests.remove(),texture("stale")); delay(12);
            });
            steps.add(() -> {
                check(Objects.equals(trait.getTexture(),expected),"stale_import_rejected_"+superseding);
                check(alice.player.messages.size()==messageCount,"stale_import_has_no_success_feedback_"+superseding);
                if(superseding.equals("permission")) grant=PermissionUtil.grantTemporary(alice.player,List.of("citizens.npc.skin"));
                current.destroy();
            });
        }
        for(String failure:List.of("http","malformed")) {
            steps.add(() -> { current=npc(false,false); select(current); trait=current.getTraitNullable(SkinTrait.class); expected=texture("unchanged"); trait.setSkinPersistent("Before","signature",expected); run("skin --url https://example.com/error.png"); messageCount=alice.player.messages.size(); awaitRequest(); });
            steps.add(() -> { Request request=requests.remove(); request.reply.complete(new Reply(failure.equals("http")?429:200,"{}".getBytes(StandardCharsets.UTF_8),Map.of())); waiting=()->alice.player.messages.size()>messageCount; });
            steps.add(() -> { check(trait.getTexture().equals(expected),"service_error_preserves_skin_"+failure); current.destroy(); });
        }
        for(String invalid:List.of("invalid.png","oversized.png")) {
            steps.add(() -> {
                Files.write(skins.resolve(invalid), invalid.startsWith("oversized") ? new byte[net.citizensnpcs.util.SkinData.MAX_IMAGE_BYTES+1] : new byte[]{1,2,3});
                current=npc(false,false); select(current); trait=current.getTraitNullable(SkinTrait.class); expected=texture("unchanged-file"); trait.setSkinPersistent("Before","signature",expected);
                run("skin --file "+invalid); messageCount=alice.player.messages.size(); waiting=()->alice.player.messages.size()>messageCount;
            });
            steps.add(() -> { check(requests.isEmpty() && expected.equals(trait.getTexture()),"invalid_png_rejected_without_upload_"+invalid); current.destroy(); });
        }
    }

    private static void nameLookups() {
        steps.add(() -> {
            current=npc(false,true); select(current); trait=current.getTraitNullable(SkinTrait.class);
            delayed=cache("oldername"); run("skin OlderName");
            var newer=cache("newername"); run("skin NewerName"); expected=texture("newer-name"); newer.complete(Optional.of(profile("NewerName",expected)));
            waiting=()->expected.equals(trait.getTexture());
        });
        steps.add(() -> { delayed.complete(Optional.of(profile("OlderName",texture("obsolete")))); delay(8); });
        steps.add(() -> {
            check(expected.equals(trait.getTexture()) && "newername".equals(trait.getSkinName()),"older_named_lookup_cannot_overwrite_newer_name");
            check("newername".equals(current.data().get(Skin.CACHED_SKIN_UUID_NAME_METADATA)),"new_name_replaces_cached_identity");
            var latest=cache("latestname"); run("skin -l LatestName");
            check(trait.shouldUpdateSkins(),"latest_flag_enables_and_accepts_name"); expected=texture("latest"); latest.complete(Optional.of(profile("LatestName",expected))); waiting=()->expected.equals(trait.getTexture());
        });
        steps.add(() -> { run("skin -l"); check(!trait.shouldUpdateSkins(),"latest_flag_toggles_off"); current.destroy(); });
        steps.add(() -> {
            current=npc(true,true); select(current); trait=current.getTraitNullable(SkinTrait.class);
            delayed=cache("pendingname"); run("skin PendingName"); expected=texture("explicit"); run("skin -t Explicit "+expected+" signature");
            delayed.complete(Optional.of(profile("PendingName",texture("obsolete")))); delay(8);
        });
        steps.add(() -> { check(expected.equals(trait.getTexture()),"named_lookup_cannot_replace_explicit_texture"); current.destroy(); });
        steps.add(() -> {
            current=npc(true,true); select(current); trait=current.getTraitNullable(SkinTrait.class);
            delayed=cache("resetname"); run("skin ResetName"); Skin.invalidate("resetname"); Skin.clearCache();
            delayed.complete(Optional.of(profile("ResetName",texture("old-cache")))); delay(8);
        });
        steps.add(() -> { check(trait.getTexture()==null,"cache_reset_invalidates_detached_inflight_lookup"); current.destroy(); });
        steps.add(() -> {
            current=npc(false,true); select(current); trait=current.getTraitNullable(SkinTrait.class);
            delayed=cache("reloadname"); run("skin ReloadName"); MemoryDataKey snapshot=new MemoryDataKey(); current.save(snapshot);
            expected=texture("reload-snapshot"); snapshot.setString("traits.skintrait.textureRaw",expected); snapshot.setString("traits.skintrait.signature","signature");
            snapshot.setString("traits.skintrait.skinName","Snapshot"); current.load(snapshot);
            delayed.complete(Optional.of(profile("ReloadName",texture("before-reload")))); delay(8);
        });
        steps.add(() -> { check(expected.equals(current.getTraitNullable(SkinTrait.class).getTexture()),"trait_reload_preserves_loaded_skin_over_pending_lookup"); current.destroy(); });
        steps.add(() -> {
            current=npc(false,true); select(current); trait=current.getTraitNullable(SkinTrait.class);
            delayed=cache("freezename"); run("skin FreezeName"); run("skin -l"); run("skin -l");
            expected=texture("first-frozen"); delayed.complete(Optional.of(profile("FreezeName",expected))); waiting=()->expected.equals(trait.getTexture());
        });
        steps.add(() -> { check(!trait.shouldUpdateSkins() && expected.equals(trait.getTexture()),"disabling_latest_keeps_initial_lookup"); current.destroy(); });
    }

    private static void exports() {
        steps.add(() -> {
            current=npc(false,false); select(current); trait=current.getTraitNullable(SkinTrait.class); trait.setSkinPersistent("Export","signature",texture("export"));
            reject("skin -e --file ../outside.png"); reject("skin -e --file wrong.txt");
            run("skin -e --file nested/export.png"); awaitRequest();
        });
        steps.add(() -> { Request request=requests.remove(); check(request.uri.getHost().equals("textures.minecraft.net"),"export_uses_trusted_mojang_host"); request.reply.complete(new Reply(200,png,Map.of("Content-Type","image/png"))); waiting=()->Files.exists(skins.resolve("nested/export.png")); });
        steps.add(() -> {
            check(Arrays.equals(png,Files.readAllBytes(skins.resolve("nested/export.png"))),"export_preserves_complete_png_over_10kb");
            run("skin -e --file nested/export.png"); messageCount=alice.player.messages.size(); awaitRequest();
        });
        steps.add(() -> { requests.remove().reply.complete(new Reply(302,new byte[]{1},Map.of("Location","http://example.com/unsafe"))); waiting=()->alice.player.messages.size()>messageCount; });
        steps.add(() -> {
            check(Arrays.equals(png,Files.readAllBytes(skins.resolve("nested/export.png"))),"failed_export_keeps_previous_file");
            trait.setSkinPersistent("Untrusted","signature",Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://example.com/skin.png\"}}}".getBytes(StandardCharsets.UTF_8)));
            reject("skin -e"); check(requests.isEmpty(),"untrusted_export_does_not_issue_request"); current.destroy();
        });
    }

    private static void bedrock() {
        for(String xuid:List.of("7","18446744073709551615")) {
            steps.add(() -> { current=npc(false,true); select(current); trait=current.getTraitNullable(SkinTrait.class); run("skin -b \"Bedrock "+xuid.charAt(0)+"\""); awaitRequest(); });
            steps.add(() -> { Request request=requests.remove(); check(request.uri.getRawPath().contains("%20"),"bedrock_name_is_encoded"); request.reply.complete(new Reply(200,("{\"xuid\":\""+xuid+"\"}").getBytes(StandardCharsets.UTF_8),Map.of())); awaitRequest(); });
            steps.add(() -> {
                Request request=requests.remove(); check(request.uri.getPath().endsWith("/skin/"+xuid),"bedrock_xuid_is_unsigned"); expected=texture("bedrock-"+xuid);
                JsonObject result=new JsonObject(); result.addProperty("value",expected); result.addProperty("signature","signature"); request.reply.complete(new Reply(200,result.toString().getBytes(StandardCharsets.UTF_8),Map.of())); waiting=()->expected.equals(trait.getTexture());
            });
            steps.add(() -> { check(profileTexture((ServerPlayer)current.getEntity()).equals(expected),"bedrock_skin_reaches_native_profile"); check(new UUID(0,Long.parseUnsignedLong(xuid)).toString().equals(current.data().get(Skin.CACHED_SKIN_UUID_METADATA)),"bedrock_short_or_unsigned_uuid"); current.destroy(); });
        }
    }

    private static void awaitRequest() { waiting=()->!requests.isEmpty(); }
    private static void delay(int ticks) { int until=server.getTickCount()+ticks; waiting=()->server.getTickCount()>=until; }
    private static void replyGenerated(Request request,String value) {
        JsonObject data=new JsonObject(); data.addProperty("value",value); data.addProperty("signature","signature");
        JsonObject texture=new JsonObject(); texture.add("data",data); JsonObject skin=new JsonObject(); skin.addProperty("uuid","fixture-source"); skin.add("texture",texture);
        JsonObject root=new JsonObject(); root.addProperty("success",true); root.add("skin",skin); request.reply.complete(new Reply(200,root.toString().getBytes(StandardCharsets.UTF_8),Map.of()));
    }
    @SuppressWarnings("unchecked") private static CompletableFuture<Optional<GameProfile>> cache(String name) throws Exception {
        var field=SkullBlockEntity.class.getDeclaredField("profileCacheByName"); field.setAccessible(true);
        var cache=(LoadingCache<String,CompletableFuture<Optional<GameProfile>>>)field.get(null);
        var result=new CompletableFuture<Optional<GameProfile>>(); cache.put(name,result); Skin.invalidate(name); return result;
    }
    private static GameProfile profile(String name,String texture) { GameProfile result=new GameProfile(UUID.randomUUID(),name); result.getProperties().put("textures",new Property("textures",texture,"signature")); return result; }
    private static String texture(String name) { return Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/"+name+"\"}}}").getBytes(StandardCharsets.UTF_8)); }
    private static String profileTexture(ServerPlayer player) { return player.getGameProfile().getProperties().get("textures").stream().map(Property::value).findFirst().orElse(""); }
    private static Set<Entity> entities() { Set<Entity> result=Collections.newSetFromMap(new IdentityHashMap<>()); level.getAllEntities().forEach(result::add); return result; }
    private static NPC npc(boolean virtual,boolean spawned) {
        NPC npc=registry.createNPC(EntityType.PLAYER,"SkinOpt"+sequence++); npcs.add(npc);
        npc.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID()); npc.getOrAddTrait(LookClose.class).setEnabled(false);
        npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false); if(virtual) npc.getOrAddTrait(PacketNPC.class);
        if(spawned) check(npc.spawn(new Location(level,4,-58,4)),"fixture_spawn"); return npc;
    }
    private static void select(NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source,npc); }
    private static void run(String command) throws Exception { check(server.getCommands().getDispatcher().execute("npc "+command,source)==1,"dispatch_"+command.split(" ")[0]); alice.pump(); }
    private static void reject(String command) throws Exception { try { check(server.getCommands().getDispatcher().execute("npc "+command,source)==0,"reject_"+command); } catch(CommandSyntaxException expected) { check(true,"reject_"+command); } }
    private static void check(boolean value,String label) { if(!value) throw new AssertionError(label); passed++; }
    private static void close() {
        if(done) return; done=true;
        try {
            for(NPC npc:npcs) npc.destroy(); Skin.clearCache();
            inFlight.forEach(request->request.reply.complete(new Reply(503,new byte[]{1},Map.of())));
            if(http!=null) http.stop(0); if(httpPool!=null) httpPool.shutdownNow();
            if(originalProxy!=null) ProxySelector.setDefault(originalProxy);
            if(grant!=null) grant.remove();
            if(alice!=null) { server.getPlayerList().remove(alice.player); alice.channel.finishAndReleaseAll(); }
            if(forced) level.setChunkForced(0,0,false);
        } catch(Throwable failure) { LoggerFactory.getLogger("citizens").error("[SKINCOMMANDAUDIT] FAILED cleanup",failure); }
        finally { server.halt(false); }
    }
    private static Actor actor(String name) {
        Actor actor = new Actor();
        ClientInformation information = ClientInformation.createDefault();
        actor.player = new AuditPlayer(name);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel channel) {
                connection.configurePacketHandler(channel.pipeline());
                channel.pipeline().addLast("skin-command-audit-capture", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                        if (message instanceof Packet<?> packet) actor.capture(packet);
                        super.write(context, message, promise);
                    }
                });
            }
        });
        NetworkRegistry.configureMockConnection(connection);
        var cookie = new CommonListenerCookie(actor.player.getGameProfile(), 0, information, false, ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
        server.getPlayerList().placeNewPlayer(connection, actor.player, cookie);
        actor.player.setPos(4, -58, 4); actor.player.setNoGravity(true);
        return actor;
    }
    private static final class AuditPlayer extends ServerPlayer {
        final List<String> messages = new ArrayList<>();
        AuditPlayer(String name) { super(SkinCommandRuntimeAudit.server, SkinCommandRuntimeAudit.level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }
    private static final class Actor {
        AuditPlayer player; EmbeddedChannel channel; int batches;
        final List<Integer> teleports = new ArrayList<>();
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            if (packet instanceof ClientboundChunkBatchFinishedPacket) batches++;
            if (packet instanceof ClientboundPlayerPositionPacket position) teleports.add(position.getId());
        }
        void pump() {
            channel.runPendingTasks();
            while (batches > 0) { batches--; player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(16)); }
            for (int id : List.copyOf(teleports)) player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
            teleports.clear();
            Object value; while ((value = channel.readOutbound()) != null) ReferenceCountUtil.release(value);
        }
    }
}
