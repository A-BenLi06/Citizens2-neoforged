package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.*;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.trait.*;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.trait.*;
import net.citizensnpcs.npc.CitizensNPCRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.*;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class CreationRuntimeAudit {
    private static final List<NPC> all = new ArrayList<>();
    private static final List<PermissionUtil.Attachment> grants = new ArrayList<>();
    private static final List<String> registries = new ArrayList<>();
    private static final Map<NPC, Integer> spawns = new IdentityHashMap<>(), removes = new IdentityHashMap<>();
    private static final Map<Integer, List<Runnable>> checks = new HashMap<>();
    private static final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private static Consumer<NPCCreateEvent> generic;
    private static Consumer<CommandSenderCreateNPCEvent> command;
    private static Consumer<NPCSpawnEvent> spawn;
    private static Consumer<NPCAddTraitEvent> traitAdded;
    private static Consumer<NPCRemoveEvent> removal;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCRegistry registry, temporary;
    private static Actor alice;
    private static CommandSourceStack source;
    private static PermissionUtil.Attachment admin;
    private static PermissionUtil.Attachment types, limits;
    private static int start = -1, deadline, passed, serial, playerEvents, commandEvents;
    private static boolean forced, done;
    private static String lastFailure = "";

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void created(NPCCreateEvent event) { if (start >= 0) all.add(event.getNPC()); if (generic != null) generic.accept(event); }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void command(CommandSenderCreateNPCEvent event) { commandEvents++; if (command != null) command.accept(event); }
    @SubscribeEvent public static void player(PlayerCreateNPCEvent event) { playerEvents++; }
    @SubscribeEvent public static void spawned(NPCSpawnEvent event) { spawns.merge(event.getNPC(), 1, Integer::sum); if (spawn != null) spawn.accept(event); }
    @SubscribeEvent public static void removed(NPCRemoveEvent event) { removes.merge(event.getNPC(), 1, Integer::sum); if (removal != null) removal.accept(event); }
    @SubscribeEvent public static void added(NPCAddTraitEvent event) { if (traitAdded != null) traitAdded.accept(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("creation-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                level.setChunkForced(0, 0, true); forced = true; deadline = server.getTickCount() + 600;
            }
            if (server.getTickCount() > deadline) throw new AssertionError("fixture_timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (alice != null) alice.pump();
            if (start < 0) {
                start = server.getTickCount(); registry = CitizensAPI.getNPCRegistry(); temporary = CitizensAPI.getTemporaryNPCRegistry();
                alice = actor("CreationAlice"); source = alice.player.createCommandSourceStack();
                grant("citizens.npc.create", "citizens.npc.spawn", "citizens.npc.undo");
                types = grant("citizens.npc.create.*"); limits = grant("citizens.admin.avoid-limits");
                admin = grant("citizens.npc.admin");
                basic(); items(); lifetimes(); vetoAndHistory(); templatesAndPermissions(); commandRegistryTargets(); repeatedBehaviorTicks();
            }
            List<Runnable> due = checks.remove(server.getTickCount() - start);
            if (due != null) due.forEach(Runnable::run);
            if (server.getTickCount() - start >= 25 && checks.isEmpty()) {
                close();
                LoggerFactory.getLogger("citizens").info("[CREATIONAUDIT] COMPLETE {} checks", passed);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[CREATIONAUDIT] FAILED", failure); close();
        }
    }

    private static void basic() throws Exception {
        NPC previous = create("--type cow -u");
        check(!previous.isSpawned() && previous.getEntity() == null && !previous.getOrAddTrait(Spawned.class).shouldSpawn(), "unspawned_shortcut");
        check(previous.getOrAddTrait(Owner.class).isOwnedBy(alice.player.getUUID()), "creator_owns_npc");
        at(previous.getStoredLocation(), 4, -58, 4, 0, 0, "unspawned_keeps_location");
        run("spawn " + previous.getId()); check(previous.isSpawned(), "unspawned_can_spawn_later");
        for (boolean packet : new boolean[] { false, true }) {
            NPC npc = create("--type cow -bs" + (packet ? "p" : "") + " --nameplate hover");
            check(npc.isSpawned() && npc.getEntity() instanceof AgeableMob age && age.isBaby(), "baby_option_" + packet);
            check(npc.data().get(NPC.Metadata.SILENT, false) && npc.data().get(NPC.Metadata.NAMEPLATE_VISIBLE).equals("hover"), "basic_metadata_" + packet);
            check((level.getEntity(npc.getEntity().getUUID()) == npc.getEntity()) == !packet, "packet_shortcut_native_membership_" + packet);
            check(spawns.getOrDefault(npc, 0) == 1, "one_initial_spawn_" + packet);
        }
        NPC defaultType = create("-up");
        check(defaultType.getOrAddTrait(MobType.class).getType() == EntityType.PLAYER && defaultType.hasTrait(PacketNPC.class), "default_player_packet_unspawned");
        command = e -> e.getNPC().getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
        for (boolean packet : new boolean[] { false, true }) {
            NPC player = create(packet ? "-p" : "");
            check(player.getEntity() instanceof ServerPlayer && (level.getEntity(player.getEntity().getUUID()) == player.getEntity()) == !packet,
                    "default_player_creation_transport_" + packet);
        }
        command = null;
        run("create LongPlayerNameForHologram -u");
        check(selected().hasTrait(HologramTrait.class) && !selected().isSpawned(), "long_name_presentation_initialized_after_construction");
        BlockPos pos = new BlockPos(2, -59, 2); blocks.put(pos, level.getBlockState(pos));
        level.setBlock(pos, Blocks.STONE_SLAB.defaultBlockState(), 3);
        NPC centered = create("--type cow -uc --at 2.8,-58.7,2.2,minecraft:overworld,80,30");
        at(centered.getStoredLocation(), 2.5, -58.5, 2.5, 0, 0, "center_uses_native_partial_collision_height");
        String named = "creation-audit-named"; registries.add(named);
        NPC inNamed = create("--type cow -u --registry " + named);
        check(inNamed.getOwningRegistry() == CitizensAPI.getNamedNPCRegistry(named), "named_registry_creation");
        run("undo"); check(!exists(inNamed), "named_creation_undo");
        NPC withTraits = create("--type cow -u --trait gravity,lookclose");
        check(withTraits.hasTrait(Gravity.class) && withTraits.hasTrait(LookClose.class), "creation_traits_applied");
        int before = count(registry); NPC selection = selected();
        for (String invalid : List.of("--type missing:entity", "--type cow --item minecraft:stone", "--type item --item no_such_item",
                "--type item --item \"minecraft:stone junk\"", "--type block_display --item minecraft:diamond",
                "--nameplate maybe", "--temporaryduration -1", "--temporaryduration P999999999999999999D",
                "--unknown true", "--at NaN,0,0", "--entitylocation invalid", "--registry \"\"", "--type cow -z")) {
            reject("create Invalid " + invalid);
            check(count(registry) == before && selected() == selection, "invalid_create_has_no_registry_or_selection_change");
        }
        String unused = "creation-invalid-registry"; registries.add(unused);
        reject("create Invalid --type cow --item minecraft:stone --registry " + unused);
        check(CitizensAPI.getNamedNPCRegistry(unused) == null, "invalid_item_does_not_create_registry");
        types.remove(); reject("create UnauthorizedType --type cow");
        check(count(registry) == before, "entity_type_permission_checked_before_creation");
        types = grant("citizens.npc.create.*");
        limits.remove(); PermissionUtil.Attachment zeroLimit = grant("citizens.npc.limit.0");
        reject("create Limited --type cow");
        check(count(registry) == before, "creation_limit_checked_before_creation");
        zeroLimit.remove(); limits = grant("citizens.admin.avoid-limits");
        int players = playerEvents, commands = commandEvents;
        server.getCommands().getDispatcher().execute("npc create Console -u --type cow", server.createCommandSourceStack());
        check(commandEvents == commands + 1 && playerEvents == players, "console_creation_event_is_not_player_event");
    }

    private static void items() throws Exception {
        for (String type : List.of("item", "item_display", "block_display", "falling_block", "item_frame", "glow_item_frame", "ominous_item_spawner")) {
            NPC npc = create("--type " + type);
            Entity entity = npc.getEntity();
            check(npc.getItemProvider().get().is(Items.STONE), "default_item_provider_" + type);
            if (entity instanceof ItemEntity item) check(item.getItem().is(Items.STONE), "default_dropped_item_is_not_empty");
            atTick(8, () -> check(npc.isSpawned() && npc.getEntity() == entity, "default_item_carrier_survives_" + type));
        }
        for (String type : List.of("item", "item_display", "item_frame", "glow_item_frame", "ominous_item_spawner", "block_display", "falling_block", "minecart")) {
            for (boolean packet : new boolean[] { false, true }) {
                boolean block = List.of("block_display", "falling_block", "minecart").contains(type);
                String item = block ? "minecraft:oak_stairs[minecraft:block_state={facing:east}]" : "minecraft:diamond[minecraft:custom_model_data=7]";
                NPC npc = create("--type " + type + " --item " + item + (packet ? " -p" : ""));
                ItemStack provided = npc.getItemProvider().get();
                check(block ? provided.is(Items.OAK_STAIRS) : provided.is(Items.DIAMOND) && provided.get(DataComponents.CUSTOM_MODEL_DATA).value() == 7,
                        "provider_components_" + type + packet);
                provided.setCount(0); check(!npc.getItemProvider().get().isEmpty(), "provider_returns_copies_" + type + packet);
                Entity entity = npc.getEntity();
                if (entity instanceof Display.ItemDisplay display) check(display.getEntityData().getNonDefaultValues().stream()
                        .anyMatch(v -> v.value() instanceof ItemStack value && ItemStack.isSameItemSameComponents(value, npc.getItemProvider().get())), "item_display_native_item");
                if (entity instanceof ItemEntity nativeItem) check(ItemStack.isSameItemSameComponents(nativeItem.getItem(), npc.getItemProvider().get()), "item_native_stack");
                if (entity instanceof ItemFrame frame) check(ItemStack.isSameItemSameComponents(frame.getItem(), npc.getItemProvider().get()), "frame_native_stack");
                BlockState state = entity instanceof Display.BlockDisplay display ? display.getEntityData().getNonDefaultValues().stream()
                        .map(v -> v.value()).filter(BlockState.class::isInstance).map(BlockState.class::cast).findFirst().orElse(null)
                        : entity instanceof FallingBlockEntity falling ? falling.getBlockState()
                        : entity instanceof AbstractMinecart cart ? cart.getDisplayBlockState() : null;
                if (state != null) check(state.is(Blocks.OAK_STAIRS)
                        && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING) == net.minecraft.core.Direction.EAST,
                        "native_block_state_" + type + packet);
                if (entity instanceof FallingBlockEntity && !packet) {
                    npc.teleport(new Location(level, 12, -58, 12), TeleportCause.PLUGIN);
                    entity.setDeltaMovement(0.3, 0, 0);
                    atTick(1, () -> check(entity.getX() > 12 && entity.getY() == -58, "falling_block_npc_uses_explicit_motion_without_gravity"));
                }
                MemoryDataKey saved = new MemoryDataKey(); npc.save(saved);
                check(saved.keyExists("itemprovider"), "provider_persisted_" + type + packet);
                atTick(8, () -> check(npc.isSpawned() && npc.getEntity() == entity, "item_carrier_survives_native_ticks_" + type + packet));
            }
        }
        int before = count(registry);
        NPC amount = registry.createNPC(EntityType.ITEM_DISPLAY, "DefaultAmount");
        amount.data().set(NPC.Metadata.ITEM_AMOUNT, 4);
        check(amount.getItemProvider().get().is(Items.STONE) && amount.getItemProvider().get().getCount() == 4, "default_provider_keeps_configured_amount");
        before = count(registry);
        try { registry.createNPCUsingItem(EntityType.COW, "UnsupportedAPI", new ItemStack(Items.STONE)); throw new AssertionError("API accepted invalid item type"); }
        catch (UnsupportedOperationException expected) { check(count(registry) == before, "unsupported_item_api_does_not_register_npc"); }
        try { registry.createNPCUsingItem(EntityType.BLOCK_DISPLAY, "InvalidBlockAPI", new ItemStack(Items.DIAMOND)); throw new AssertionError("API accepted nonblock"); }
        catch (IllegalArgumentException expected) { check(count(registry) == before, "invalid_block_item_api_does_not_register_npc"); }
        ItemStack original = new ItemStack(Items.DIAMOND, 3);
        NPC isolated = registry.createNPCUsingItem(EntityType.ITEM_DISPLAY, "ProviderCopy", original);
        original.setCount(0); check(isolated.getItemProvider().get().getCount() == 3, "provider_does_not_capture_mutable_caller_stack");
        BlockPos controlPos = new BlockPos(12, -55, 10);
        for (int y = -61; y <= -55; y++) {
            BlockPos p = new BlockPos(12, y, 10); blocks.putIfAbsent(p, level.getBlockState(p));
        }
        level.setBlock(controlPos, Blocks.SAND.defaultBlockState(), 3);
        FallingBlockEntity falling = FallingBlockEntity.fall(level, controlPos, Blocks.SAND.defaultBlockState());
        atTick(1, () -> check(falling.getY() < -55, "vanilla_falling_block_keeps_native_gravity"));
        atTick(25, falling::discard);
        OminousItemSpawner ominous = EntityType.OMINOUS_ITEM_SPAWNER.create(level);
        ominous.setPos(14, -58, 14); ominous.setItem(ItemStack.EMPTY);
        check(level.addFreshEntity(ominous), "vanilla_spawner_control_admitted");
        atTick(1, () -> check(ominous.isRemoved(), "vanilla_spawner_keeps_native_lifecycle"));
    }

    private static void lifetimes() throws Exception {
        String unused = "creation-unused-registry"; registries.add(unused);
        NPC flagOnly = create("--type cow -ut --registry " + unused);
        check(flagOnly.getOwningRegistry() == temporary && CitizensAPI.getNamedNPCRegistry(unused) == null, "temporary_wins_without_unused_registry");
        NPC zero = create("--type cow -u --temporaryduration 0");
        check(exists(zero), "zero_lifetime_not_destroyed_inside_creation");
        atTick(1, () -> check(!exists(zero), "zero_lifetime_expires_next_tick"));
        for (String duration : List.of("5", "5t", "PT0.25S")) {
            NPC npc = create("--type cow -up --temporaryduration " + duration);
            check(npc.getOwningRegistry() == temporary && !npc.isSpawned(), "duration_implies_temporary_registry");
            atTick(4, () -> check(exists(npc), "lifetime_not_early_" + duration));
            atTick(5, () -> check(!exists(npc) && removes.getOrDefault(npc, 0) == 1, "lifetime_exact_deadline_" + duration));
        }
        NPC live = create("--type cow -p --temporaryduration 10t");
        atTick(9, () -> check(exists(live) && live.isSpawned(), "live_temporary_not_early"));
        atTick(10, () -> check(!exists(live) && live.getEntity() == null, "live_temporary_releases_entity"));
        NPC replaced = create("--type cow -u --temporaryduration 5");
        UUID uuid = replaced.getUniqueId(); int id = replaced.getId(); replaced.destroy();
        NPC replacement = temporary.createNPC(EntityType.COW, uuid, id, "Replacement");
        atTick(6, () -> check(exists(replacement), "old_lifetime_does_not_destroy_replacement"));
        NPC undone = create("--type cow -up --temporaryduration 5"); run("undo");
        atTick(6, () -> check(!exists(undone) && removes.getOrDefault(undone, 0) == 1, "undo_cancels_lifetime"));
        NPC large = create("--type cow -u --temporaryduration P2000D");
        atTick(25, () -> check(exists(large) && exists(flagOnly), "large_duration_does_not_overflow_and_plain_t_is_session_only"));
    }

    private static void vetoAndHistory() throws Exception {
        NPC previous = selected(); int before = count(registry); int messages = alice.player.messages.size();
        generic = e -> e.setCanceled(true);
        reject("create GenericVeto --type cow"); generic = null;
        check(count(registry) == before && selected() == previous, "registry_veto_cleans_candidate");
        command = e -> { check(e.getSender().getEntity() == alice.player && !e.getNPC().isSpawned(), "command_event_has_source_and_unspawned_candidate"); e.setCancelReason("fixture veto"); e.setCanceled(true); };
        reject("create CommandVeto --type cow --temporaryduration 5"); command = null;
        check(selected() == previous && count(registry) == before, "command_veto_preserves_selection");
        check(lastFailure.contains("fixture veto"), "command_veto_reason_reaches_dispatcher");
        String unused = "creation-veto-registry"; registries.add(unused);
        command = e -> e.setCanceled(true); reject("create RegistryVeto --type cow --registry " + unused); command = null;
        check(CitizensAPI.getNamedNPCRegistry(unused) == null, "veto_releases_new_empty_registry");
        command = e -> check(!e.getNPC().spawn(new Location(level, 9, -58, 9)), "preparation_blocks_transient_spawn");
        NPC prepared = create("--type cow -u"); command = null;
        check(!prepared.isSpawned() && spawns.getOrDefault(prepared, 0) == 0, "preparation_has_no_spawn_event");
        NPC[] survivor = { null };
        command = e -> {
            command = null;
            NPC old = e.getNPC();
            survivor[0] = old.getOwningRegistry().createNPC(EntityType.COW, old.getUniqueId(), old.getId(), "CallbackReplacement");
            e.setCanceled(true);
        };
        reject("create Replaced --type cow"); command = null;
        check(exists(survivor[0]), "canceled_creation_cannot_deregister_callback_replacement");
        NPC undo = create("--type cow -p");
        run("undo"); check(!exists(undo) && undo.getEntity() == null, "creation_is_recorded_in_undo_history");
        NPC original = create("--type cow -u");
        NPC[] nested = { null };
        removal = e -> {
            if (e.getNPC() != original) return;
            removal = null;
            try { nested[0] = create("--type cow -u"); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        };
        run("undo"); removal = null;
        check(!exists(original) && nested[0] != null && exists(nested[0]), "undo_callback_can_create_a_new_history_entry");
        run("undo");
        check(!exists(nested[0]), "undo_preserves_and_then_removes_callback_history_entry");
        NPC canceledSpawn;
        spawn = e -> e.setCanceled(true);
        canceledSpawn = create("--type cow"); spawn = null;
        check(exists(canceledSpawn) && !canceledSpawn.isSpawned() && !canceledSpawn.getOrAddTrait(Spawned.class).shouldSpawn(), "spawn_veto_retains_unspawned_creation");
        run("undo"); check(!exists(canceledSpawn), "spawn_veto_creation_can_be_undone");
        before = count(registry); previous = selected();
        traitAdded = e -> {
            if (e.getNPC().getRawName().equals("RemovedDuringInit")) e.getNPC().destroy();
        };
        reject("create RemovedDuringInit --type cow"); traitAdded = null;
        check(count(registry) == before && selected() == previous, "trait_callback_cannot_resurrect_creation_candidate");
        command = e -> { throw new IllegalStateException("expected creation listener failure"); };
        reject("create ThrowingHook --type cow"); command = null;
        check(count(registry) == before, "throwing_creation_hook_is_cleaned");
        NPC[] canceled = { null };
        command = e -> { canceled[0] = e.getNPC(); e.setCanceled(true); };
        removal = e -> { if (e.getNPC() == canceled[0]) throw new IllegalStateException("expected removal listener failure"); };
        reject("create CleanupFailure --type cow"); command = null; removal = null;
        check(!exists(canceled[0]) && removes.getOrDefault(canceled[0], 0) == 1, "throwing_remove_hook_still_releases_candidate");
        canceled[0].destroy(); check(removes.get(canceled[0]) == 1, "destroy_is_idempotent");
    }

    private static void templatesAndPermissions() throws Exception {
        NPC removedByParser = registry.createNPC(EntityType.COW, "RemovedByTemplateParser");
        check(net.citizensnpcs.api.npc.templates.TraitOwnershipProbe.verify(removedByParser),
                "trait_parser_cannot_attach_to_or_continue_after_removed_target");
        NPC defaultParserTarget = registry.createNPC(EntityType.COW, "DefaultTemplateParser");
        try {
            check(net.citizensnpcs.api.npc.templates.TraitOwnershipProbe.verifyDefaultParser(defaultParserTarget),
                    "default_trait_template_rejects_invalid_fields_and_accepts_repair");
        } finally { defaultParserTarget.destroy(); }
        NPC template = registry.createNPC(EntityType.COW, "TemplateSource");
        template.getOrAddTrait(Owner.class).setOwner(alice.player.getUUID());
        template.getOrAddTrait(Age.class).setAge(-12000);
        DataKey memoryTree = new MemoryDataKey(); memoryTree.setString("root", "succeed");
        BehaviorTrait templateBehavior = template.getOrAddTrait(BehaviorTrait.class);
        templateBehavior.applyBehaviorsFromKey(memoryTree);
        templateBehavior.getMemory().set("template_list", Arrays.asList("first", null, "last"));
        templateBehavior.getMemory().set("template_empty", List.of());
        template.spawn(new Location(level, 10, -58, 10));
        NPC selectionBeforeTemplateCheck = selected();
        var consoleSource = server.createCommandSourceStack();
        NPC previousConsoleSelection = CitizensAPI.getDefaultNPCSelector().getSelected(consoleSource);
        try {
            for (String selector : List.of("select " + template.getId(), "sel --name TemplateSource",
                    "select --uuid " + template.getUniqueId(), "select TemplateSource")) {
                CitizensAPI.getDefaultNPCSelector().deselect(consoleSource);
                new net.citizensnpcs.api.npc.templates.CommandListExecutor(List.of("npc " + selector)).accept(template);
                check(CitizensAPI.getDefaultNPCSelector().getSelected(consoleSource) == template,
                        "template_command_preserves_explicit_selector_" + selector);
            }
        } finally {
            if (previousConsoleSelection != null && exists(previousConsoleSelection))
                CitizensAPI.getDefaultNPCSelector().select(consoleSource, previousConsoleSelection);
            else CitizensAPI.getDefaultNPCSelector().deselect(consoleSource);
        }
        var generationPermission = grant("citizens.templates.generate");
        CitizensAPI.getDefaultNPCSelector().select(source, template);
        String emptyNamespace = "empty_name_audit_" + UUID.randomUUID();
        boolean expectedTemplateError = false;
        String observedTemplateError = "command did not fail";
        try {
            try { server.getCommands().getDispatcher().execute("template generate " + emptyNamespace + ":", source); }
            catch (CommandSyntaxException failure) {
                observedTemplateError = failure.getMessage();
                expectedTemplateError = failure.getMessage().equals(TextParser.parse(Messaging.convertLegacyCodes(
                        Messaging.tryTranslate(net.citizensnpcs.util.Messages.TEMPLATE_MISSING))).getString());
            }
            if (!expectedTemplateError) throw new AssertionError("Unexpected template command response: " + observedTemplateError);
            check(expectedTemplateError && !Files.exists(CitizensAPI.getDataFolder().toPath().resolve("templates").resolve(emptyNamespace)),
                    "empty_template_name_is_a_command_error_without_filesystem_changes");
        } finally {
            generationPermission.remove();
            if (selectionBeforeTemplateCheck != null && exists(selectionBeforeTemplateCheck))
                CitizensAPI.getDefaultNPCSelector().select(source, selectionBeforeTemplateCheck);
            else CitizensAPI.getDefaultNPCSelector().deselect(source);
        }
        ResourceLocation key = ResourceLocation.fromNamespaceAndPath("creation_audit", "stored_spawn");
        CitizensAPI.getTemplateRegistry().generateTemplateFromNPC(key, template);
        CitizensAPI.getTemplateRegistry().generateTemplateFromNPC(
                ResourceLocation.fromNamespaceAndPath("creation_audit", "sibling"), template);
        template.getOrAddTrait(Age.class).setAge(-6000);
        CitizensAPI.getTemplateRegistry().generateTemplateFromNPC(key, template);
        check(CitizensAPI.getTemplateRegistry().getTemplates(key.getPath()).size() == 1
                && CitizensAPI.getTemplateRegistry().getTemplates(key.getPath()).iterator().next()
                        == CitizensAPI.getTemplateRegistry().getTemplateByKey(key), "overwritten_template_indexes_agree");
        template.destroy();
        int before = count(registry); NPC previous = selected();
        admin.remove();
        reject("create Unauthorized --type cow --template " + key);
        reject("create Unauthorized --type cow --registry forbidden");
        check(count(registry) == before && selected() == previous, "advanced_options_require_admin_before_creation");
        admin = grant("citizens.npc.admin");
        NPC copied = create("--type cow -up --template " + key + " --at 6,-58,6,minecraft:overworld");
        check(!copied.isSpawned() && spawns.getOrDefault(copied, 0) == 0 && !copied.getOrAddTrait(Spawned.class).shouldSpawn(),
                "unspawned_template_never_transiently_spawns");
        at(copied.getStoredLocation(), 6, -58, 6, 0, 0, "command_destination_wins_over_template_location");
        check(copied.getTraitNullable(Age.class).getAge() == -6000, "template_traits_still_apply");
        NPC diskTemplateTarget = registry.createNPC(EntityType.COW, "DiskTemplateTarget");
        try {
            var diskTemplates = net.citizensnpcs.api.npc.templates.TemplateRegistry.prepareReload(
                    CitizensAPI.getDataFolder().toPath().resolve("templates"));
            diskTemplates.getTemplateByKey(key).apply(diskTemplateTarget);
            var memory = diskTemplateTarget.getTraitNullable(BehaviorTrait.class).getMemory();
            check(memory.getList("template_list").equals(Arrays.asList("first", null, "last"))
                    && memory.has("template_empty") && memory.getList("template_empty").isEmpty(),
                    "generated_template_disk_roundtrip_preserves_native_blackboard_lists");
        } finally { diskTemplateTarget.destroy(); }
        NPC behaviorTarget = registry.createNPC(EntityType.COW, "BehaviorTemplateFailure");
        Path workspace = Files.createDirectories(CitizensAPI.getDataFolder().toPath().resolve("behavior-template-audit"));
        String missing = "missing-" + UUID.randomUUID() + ".yml";
        Path malformed = workspace.resolve("malformed-" + UUID.randomUUID() + ".yml");
        Path validBehavior = workspace.resolve("valid tree-" + UUID.randomUUID() + ".yml");
        Files.writeString(malformed, "tree: [unfinished\n");
        Files.writeString(validBehavior, "sequence: []\n");
        var parser = BehaviorTrait.createTemplateParser().getShortTemplateParser();
        var context = new net.citizensnpcs.api.trait.TraitTemplateParser.TraitParserContext(behaviorTarget,
                new net.citizensnpcs.api.npc.templates.TemplateWorkspace(workspace.toFile()));
        String[][] inputs = { {"behavior"}, {"behavior", missing}, {"behavior", malformed.getFileName().toString()} };
        String[] messages = { "requires a file name", "Behavior file not found", "Unable to read behavior template" };
        try {
            for (int i = 0; i < inputs.length; i++) {
                RuntimeException failure = null;
                try { parser.apply(context, new net.citizensnpcs.api.command.CommandContext(inputs[i])); }
                catch (RuntimeException expected) { failure = expected; }
                check(failure != null && failure.getMessage().contains(messages[i])
                        && !behaviorTarget.hasTrait(BehaviorTrait.class) && exists(behaviorTarget),
                        "behavior_template_input_failure_preserves_npc_" + i);
            }
            parser.apply(context, new net.citizensnpcs.api.command.CommandContext(
                    ("behavior " + validBehavior.getFileName()).split(" ")));
            check(behaviorTarget.hasTrait(BehaviorTrait.class) && behaviorTarget.getTraitNullable(BehaviorTrait.class).getMemory() != null,
                    "behavior_template_short_form_loads_filename_with_spaces");
            var runs = new java.util.concurrent.atomic.AtomicInteger();
            CitizensAPI.getBehaviorRegistry().registerBehavior("template_preserve_probe", (params, ctx) ->
                    (net.citizensnpcs.api.ai.tree.InstantBehavior) () -> {
                        runs.incrementAndGet(); return net.citizensnpcs.api.ai.tree.BehaviorStatus.SUCCESS;
                    });
            var behavior = behaviorTarget.getTraitNullable(BehaviorTrait.class);
            DataKey goodTree = new MemoryDataKey(); goodTree.setString("root", "template_preserve_probe");
            behavior.applyBehaviorsFromKey(goodTree);
            behaviorTarget.getDefaultBehaviorController().run();
            var oldMemory = behavior.getMemory();
            DataKey invalidTree = new MemoryDataKey(); invalidTree.setString("root", "missing_behavior_" + UUID.randomUUID());
            boolean rejected = false;
            try { behavior.applyBehaviorsFromKey(invalidTree); }
            catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains("Unknown behavior"); }
            behaviorTarget.getDefaultBehaviorController().run();
            check(rejected && runs.get() == 2 && behavior.getMemory() == oldMemory,
                    "invalid_behavior_replacement_preserves_running_tree");
            DataKey savedBehavior = new MemoryDataKey(); behavior.save(savedBehavior);
            check("template_preserve_probe".equals(savedBehavior.getString("tree.root")),
                    "invalid_behavior_replacement_preserves_saved_tree");
            DataKey secondSave = new MemoryDataKey(); behavior.save(secondSave);
            check(savedBehavior.getRaw("tree").equals(secondSave.getRaw("tree")),
                    "behavior_definition_survives_repeated_fresh_snapshots");
            NPC loadedTarget = registry.createNPC(EntityType.COW, "LoadedBehavior");
            try {
                BehaviorTrait loadedBehavior = loadedTarget.getOrAddTrait(BehaviorTrait.class);
                loadedBehavior.load(savedBehavior.copyWithRoot());
                DataKey resaved = new MemoryDataKey(); loadedBehavior.save(resaved);
                check(savedBehavior.getRaw("tree").equals(resaved.getRaw("tree")),
                        "loaded_behavior_definition_is_resaved");
            } finally { loadedTarget.destroy(); }
            check(behaviorTarget.spawn(new Location(level, 12, -58, 12)), "behavior_lifecycle_initial_spawn");
            check(behaviorTarget.despawn(DespawnReason.PENDING_RESPAWN)
                    && behaviorTarget.spawn(new Location(level, 12, -58, 12)), "behavior_lifecycle_respawn");
            int beforeRun = runs.get();
            behaviorTarget.getDefaultBehaviorController().run();
            check(runs.get() == beforeRun + 1, "behavior_execution_resumes_after_respawn");
            DataKey parallelTree = new MemoryDataKey();
            parallelTree.setRaw("parallel", Map.of("0", "set native_parallel_first `1`",
                    "1", "set native_parallel_second `2`", "2", "`list.add('native_list', 'kept')`"));
            behavior.applyBehaviorsFromKey(parallelTree);
            behaviorTarget.getDefaultBehaviorController().run();
            check(behavior.getMemory().getNumber("native_parallel_first") == 1D
                    && behavior.getMemory().getNumber("native_parallel_second") == 2D,
                    "native_parallel_tree_executes_all_children");
            DataKey memorySnapshot = new MemoryDataKey(); behavior.save(memorySnapshot);
            var recoveredMemory = new net.citizensnpcs.api.expr.Memory();
            recoveredMemory.loadFrom(memorySnapshot.getRelative("memory"));
            check(recoveredMemory.getList("native_list").equals(List.of("kept")),
                    "native_behavior_string_list_survives_snapshot");
            behavior.applyBehaviorsFromKey(goodTree);
            behaviorTarget.removeTrait(BehaviorTrait.class);
            int afterRemoval = runs.get();
            behaviorTarget.getDefaultBehaviorController().run();
            check(runs.get() == afterRemoval && !behaviorTarget.hasTrait(BehaviorTrait.class),
                    "removed_behavior_trait_stops_executing");
        } finally { Files.deleteIfExists(malformed); Files.deleteIfExists(validBehavior); behaviorTarget.destroy(); }
        NPC restoreTarget = registry.createNPC(EntityType.COW, "TemplateFailureRestore");
        try {
            check(restoreTarget.spawn(new Location(level, 14, -58, 14)), "template_failure_restore_initial_spawn");
            Entity originalEntity = restoreTarget.getEntity();
            DataKey invalidTemplate = new MemoryDataKey(); invalidTemplate.setRaw("traits", Map.of("0", "behavior"));
            var invalid = net.citizensnpcs.api.npc.templates.Template.load(
                    new net.citizensnpcs.api.npc.templates.TemplateRegistry.TemplateErrorReporter(),
                    new net.citizensnpcs.api.npc.templates.TemplateWorkspace(workspace.toFile()),
                    ResourceLocation.fromNamespaceAndPath("creation_audit", "failure"), invalidTemplate);
            boolean rejected = false;
            try { invalid.apply(restoreTarget); }
            catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains("requires a file name"); }
            check(rejected, "template_action_failure_is_reported");
            check(restoreTarget.isSpawned() && exists(restoreTarget)
                    && originalEntity.isRemoved() && restoreTarget.getEntity() != originalEntity,
                    "template_action_failure_restores_native_spawn");
            var eventCommands = new java.util.concurrent.atomic.AtomicInteger();
            server.getCommands().getDispatcher().register(net.minecraft.commands.Commands.literal("creation_template_event")
                    .executes(ctx -> { eventCommands.incrementAndGet(); return 1; }));
            DataKey eventDefinition = new MemoryDataKey();
            eventDefinition.setRaw("commands", Map.of("on_spawn", Map.of("0", "creation_template_event")));
            var eventTemplate = net.citizensnpcs.api.npc.templates.Template.load(
                    new net.citizensnpcs.api.npc.templates.TemplateRegistry.TemplateErrorReporter(),
                    new net.citizensnpcs.api.npc.templates.TemplateWorkspace(workspace.toFile()),
                    ResourceLocation.fromNamespaceAndPath("creation_audit", "event"), eventDefinition);
            eventTemplate.apply(restoreTarget);
            check(eventCommands.get() == 1, "template_spawn_command_runs_on_first_application");
            eventTemplate.apply(restoreTarget);
            check(eventCommands.get() == 2, "repeated_template_application_does_not_duplicate_listener");
            NPC unrelated = registry.createNPC(EntityType.COW, "UnrelatedTemplateEvent");
            try {
                if (!unrelated.spawn(new Location(level, 16, -58, 16))) throw new AssertionError("Event isolation setup failed");
                check(eventCommands.get() == 2, "template_spawn_command_ignores_unapplied_npc");
            } finally { unrelated.destroy(); }
            NPC shared = registry.createNPC(EntityType.COW, "SharedTemplateEvent");
            try {
                if (!shared.spawn(new Location(level, 18, -58, 16))) throw new AssertionError("Shared template setup failed");
                eventTemplate.apply(shared);
                var replacementCommands = new java.util.concurrent.atomic.AtomicInteger();
                server.getCommands().getDispatcher().register(net.minecraft.commands.Commands.literal("creation_template_replacement")
                        .executes(ctx -> { replacementCommands.incrementAndGet(); return 1; }));
                Path eventFolder = workspace.resolve("event-reload");
                Files.createDirectories(eventFolder.resolve("creation_audit"));
                Path eventFile = eventFolder.resolve("creation_audit/events.yml");
                Files.writeString(eventFile, "event:\n  commands:\n    on_spawn:\n      - creation_template_replacement\n");
                var reloadedEvents = net.citizensnpcs.api.npc.templates.TemplateRegistry.prepareReload(eventFolder);
                reloadedEvents.getTemplateByKey(eventTemplate.getKey()).apply(restoreTarget);
                check(eventCommands.get() == 3 && replacementCommands.get() == 1,
                        "reloaded_template_reapplication_replaces_old_spawn_commands");
                eventTemplate.apply(shared);
                check(eventCommands.get() == 4 && replacementCommands.get() == 1,
                        "reapplication_preserves_other_npc_template_commands");
                Files.writeString(eventFile, "event: {}\n");
                net.citizensnpcs.api.npc.templates.TemplateRegistry.prepareReload(eventFolder)
                        .getTemplateByKey(eventTemplate.getKey()).apply(restoreTarget);
                check(eventCommands.get() == 4 && replacementCommands.get() == 1,
                        "reapplication_removes_deleted_event_commands");
            } finally { shared.destroy(); }
        } finally { restoreTarget.destroy(); }
    }

    private static NPC create(String options) throws Exception { run("create C" + serial++ + " " + options); NPC npc = selected(); check(npc != null && exists(npc), "created_and_selected"); return npc; }
    private static void commandRegistryTargets() {
        NPC original = registry.createNPC(EntityType.COW, "DefaultCommandTarget");
        String name = "command_targets_" + UUID.randomUUID();
        NPCRegistry named = CitizensAPI.createInMemoryNPCRegistry(name);
        registries.add(name);
        NPCRegistry anonymous = CitizensAPI.createAnonymousNPCRegistry(new net.citizensnpcs.api.npc.MemoryNPCDataStore());
        for (NPCRegistry other : List.of(named, anonymous, temporary)) {
            NPC target = other.createNPC(EntityType.COW, original.getUniqueId(), original.getId(), "OtherCommandTarget");
            new net.citizensnpcs.api.npc.templates.CommandListExecutor(List.of("npc   rename ExactTarget")).accept(target);
            check(target.getName().equals("ExactTarget") && original.getName().equals("DefaultCommandTarget"),
                    "implicit_template_target_keeps_registry_identity_" + other.getName());
            net.citizensnpcs.util.Util.scheduleCommand(target, null, "npc rename ScheduledTarget", true, false);
            atTick(3, () -> check(target.getName().equals("ScheduledTarget") && original.getName().equals("DefaultCommandTarget"),
                    "scheduled_target_keeps_registry_identity_" + other.getName()));
        }
        NPC retired = named.createNPC(EntityType.COW, "RetiredCommandTarget");
        int id = retired.getId(); UUID uuid = retired.getUniqueId();
        net.citizensnpcs.util.Util.scheduleCommand(retired, null, "npc rename WrongReplacement", true, false);
        retired.destroy();
        NPC replacement = named.createNPC(EntityType.COW, uuid, id, "ReplacementCommandTarget");
        atTick(3, () -> check(replacement.getName().equals("ReplacementCommandTarget"), "scheduled_target_rejects_replacement"));
        NPC namedTarget = named.getById(original.getId());
        new net.citizensnpcs.api.npc.templates.CommandListExecutor(List.of("npc rename ExplicitDefault --id " + original.getId())).accept(namedTarget);
        check(original.getName().equals("ExplicitDefault") && namedTarget.getName().equals("ExactTarget"), "explicit_id_keeps_default_registry_lookup");
        original.setName("DefaultCommandTarget");
        var signals = CitizensAPI.getBehaviorRegistry().getSignalManager();
        int[] delivered = new int[2];
        signals.listen(original, "signal_instance_audit", () -> delivered[0]++);
        signals.listen(namedTarget, "signal_instance_audit", () -> delivered[1]++);
        try {
            DataKey params = new MemoryDataKey();
            params.setString("npc", Integer.toString(original.getId()));
            params.setString("signal", "signal_instance_audit");
            var context = new net.citizensnpcs.api.ai.tree.BehaviorRegistry.BehaviorContext(namedTarget,
                    new net.citizensnpcs.api.expr.ExpressionScope(), CitizensAPI.getExpressionRegistry(),
                    new net.citizensnpcs.api.expr.Memory());
            var emit = CitizensAPI.getBehaviorRegistry().createBehavior("emit_signal_to", params, context);
            check(emit.run() == net.citizensnpcs.api.ai.tree.BehaviorStatus.SUCCESS && delivered[0] == 1 && delivered[1] == 0,
                    "directed_signal_behavior_uses_resolved_instance");
            signals.emit(namedTarget, "signal_instance_audit");
            check(delivered[0] == 1 && delivered[1] == 1, "native_signal_instance_emission_isolated");
            signals.emitToNPC(original.getUniqueId(), "signal_instance_audit");
            check(delivered[0] == 2 && delivered[1] == 2, "native_explicit_uuid_signal_matches_instances");
            signals.clearListeners(original); signals.emit(namedTarget, "signal_instance_audit");
            check(delivered[0] == 2 && delivered[1] == 3, "native_signal_cleanup_preserves_other_instance");
        } finally { signals.clearListeners(original); signals.clearListeners(namedTarget); }
    }

    private static void repeatedBehaviorTicks() {
        for (boolean packet : new boolean[]{false, true}) {
            NPC target = registry.createNPC(EntityType.COW, "RepeatedBehavior" + packet);
            if (packet) target.getOrAddTrait(PacketNPC.class);
            var behavior = target.getOrAddTrait(BehaviorTrait.class);
            DataKey definition = new MemoryDataKey();
            definition.setRaw("sequence", Map.of(
                    "0", Map.of("repeat 0", Map.of("0", "set skipped `1`")),
                    "1", Map.of("repeat 3", Map.of("0", "wait `2`", "1", "set repetitions `mem.get('repetitions') + 1`")),
                    "2", "set repeat_finished `1`",
                    "3", "wait_for_signal repeat_audit_hold",
                    "4", "set signal_finished `1`",
                    "5", "wait_for_signal repeat_audit_final_hold"));
            behavior.applyBehaviorsFromKey(definition);
            behavior.getMemory().set("repetitions", 0D);
            check(target.spawn(new Location(level, 22, -58, 4)), "repeat_behavior_spawn_" + packet);
            atTick(2, () -> check(behavior.getMemory().getNumber("repetitions") < 3
                    && behavior.getMemory().getNumber("repeat_finished") == 0,
                    "repeat_wait_yields_across_server_ticks_" + packet));
            atTick(20, () -> {
                check(behavior.getMemory().getNumber("repetitions") == 3
                        && behavior.getMemory().getNumber("repeat_finished") == 1
                        && behavior.getMemory().getNumber("skipped") == 0,
                        "repeat_body_completes_exactly_three_times_" + packet);
                CitizensAPI.getBehaviorRegistry().getSignalManager().emit(target, "repeat_audit_hold");
            });
            atTick(23, () -> {
                check(behavior.getMemory().getNumber("signal_finished") == 1,
                        "native_signal_resumes_waiting_tree_" + packet);
                target.destroy();
            });
        }
    }

    private static NPC selected() { return CitizensAPI.getDefaultNPCSelector().getSelected(source); }
    private static void run(String command) throws Exception { check(server.getCommands().getDispatcher().execute("npc " + command, source) == 1, "dispatch_" + command); alice.pump(); }
    private static void reject(String command) throws Exception {
        lastFailure = "";
        try { check(server.getCommands().getDispatcher().execute("npc " + command, source) == 0, "reject_" + command); }
        catch (CommandSyntaxException expected) { lastFailure = expected.getMessage(); check(true, "reject_" + command); }
    }
    private static PermissionUtil.Attachment grant(String... permissions) { var value = PermissionUtil.grantTemporary(alice.player, List.of(permissions)); grants.add(value); return value; }
    private static int count(NPCRegistry in) { int size = 0; for (NPC npc : in) size++; return size; }
    private static boolean exists(NPC npc) { return npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc && npc.getOwningRegistry().getById(npc.getId()) == npc; }
    private static void atTick(int tick, Runnable action) { checks.computeIfAbsent(tick, unused -> new ArrayList<>()).add(action); }
    private static void at(Location loc, double x, double y, double z, float yaw, float pitch, String label) {
        check(loc != null && loc.getWorld() == level && Math.abs(loc.getX()-x)<1e-6 && Math.abs(loc.getY()-y)<1e-6 && Math.abs(loc.getZ()-z)<1e-6
                && Math.abs(loc.getYaw()-yaw)<1e-4 && Math.abs(loc.getPitch()-pitch)<1e-4, label + " actual=" + loc);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; }
    private static void close() {
        if (done) return; done = true; generic = null; command = null; spawn = null; traitAdded = null; removal = null;
        try {
            for (NPC npc : List.copyOf(all)) {
                NPCRegistry owner = npc.getOwningRegistry();
                if (owner.getByUniqueId(npc.getUniqueId()) == npc || owner.getById(npc.getId()) == npc || npc.getEntity() != null) npc.destroy();
            }
            for (String name : registries) if (CitizensAPI.getNamedNPCRegistry(name) != null) CitizensAPI.removeNamedNPCRegistry(name);
            blocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
            grants.forEach(PermissionUtil.Attachment::remove);
            if (alice != null) { server.getPlayerList().remove(alice.player); alice.channel.finishAndReleaseAll(); }
            if (forced) level.setChunkForced(0, 0, false);
        } catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[CREATIONAUDIT] FAILED cleanup", failure); }
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
                channel.pipeline().addLast("creation-audit-capture", new ChannelOutboundHandlerAdapter() {
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
        AuditPlayer(String name) { super(CreationRuntimeAudit.server, CreationRuntimeAudit.level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
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
