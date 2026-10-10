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
    private static Actor alice, cooldownPlayer;
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
            if (cooldownPlayer != null) cooldownPlayer.pump();
            if (start < 0) {
                start = server.getTickCount(); registry = CitizensAPI.getNPCRegistry(); temporary = CitizensAPI.getTemporaryNPCRegistry();
                alice = actor("CreationAlice"); source = alice.player.createCommandSourceStack();
                grant("citizens.npc.create", "citizens.npc.spawn", "citizens.npc.undo");
                types = grant("citizens.npc.create.*"); limits = grant("citizens.admin.avoid-limits");
                admin = grant("citizens.npc.admin");
                basic(); items(); lifetimes(); vetoAndHistory(); templatesAndPermissions(); commandRegistryTargets(); behaviorSnapshotMemory(); behaviorRootLifecycle(); repeatedBehaviorTicks(); scalarBehaviorTicks(); conditionalContainerTicks(); retryBehaviorTicks(); timerBehaviorTicks(); globalCommandCooldown(); mappedBehaviorTicks();
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
            DataKey ambiguousTree = new MemoryDataKey();
            ambiguousTree.setRaw("", Map.of("sequence", Map.of("0", "succeed"), "invert", Map.of("0", "fail")));
            rejected = false;
            try { behavior.applyBehaviorsFromKey(ambiguousTree); }
            catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains("exactly one root"); }
            int beforeAmbiguousRun = runs.get();
            behaviorTarget.getDefaultBehaviorController().run();
            check(rejected && runs.get() == beforeAmbiguousRun + 1 && behavior.getMemory() == oldMemory,
                    "multiple_behavior_roots_preserve_running_tree");
            DataKey afterAmbiguous = new MemoryDataKey(); behavior.save(afterAmbiguous);
            check(savedBehavior.getRaw("tree").equals(afterAmbiguous.getRaw("tree")),
                    "multiple_behavior_roots_preserve_saved_definition");
            for (String duration : List.of("-1", "not-a-duration", "2147483648")) {
                DataKey invalidWait = new MemoryDataKey(); invalidWait.setString("wait", duration);
                rejected = false;
                try { behavior.applyBehaviorsFromKey(invalidWait); }
                catch (IllegalArgumentException expected) { rejected = true; }
                int beforeInvalidWait = runs.get();
                behaviorTarget.getDefaultBehaviorController().run();
                check(rejected && runs.get() == beforeInvalidWait + 1 && behavior.getMemory() == oldMemory,
                        "invalid_wait_literal_preserves_running_tree_" + duration);
                DataKey afterWait = new MemoryDataKey(); behavior.save(afterWait);
                check(savedBehavior.getRaw("tree").equals(afterWait.getRaw("tree")),
                        "invalid_wait_literal_preserves_saved_definition_" + duration);
            }
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

    private static void behaviorSnapshotMemory() {
        var builds = new java.util.concurrent.atomic.AtomicInteger();
        var runs = new java.util.concurrent.atomic.AtomicInteger();
        var rejectedInput = new java.util.concurrent.atomic.AtomicInteger();
        CitizensAPI.getBehaviorRegistry().registerBehavior("creation_snapshot_capture", (params, context) -> {
            builds.incrementAndGet();
            double captured = context.getMemory().getNumber("snapshot_input", -1);
            Object raw = context.getMemory().get("snapshot_list");
            var list = raw instanceof List<?> values ? new ArrayList<>(values) : List.of();
            return (net.citizensnpcs.api.ai.tree.InstantBehavior) () -> {
                runs.incrementAndGet();
                context.getMemory().set("factory_input", captured);
                context.getMemory().set("factory_list", list);
                return net.citizensnpcs.api.ai.tree.BehaviorStatus.SUCCESS;
            };
        });
        CitizensAPI.getBehaviorRegistry().registerBehavior("creation_snapshot_reject", (params, context) -> {
            rejectedInput.set((int) context.getMemory().getNumber("snapshot_input", -1));
            context.getMemory().set("rejected_factory_write", 1);
            throw new IllegalArgumentException("expected snapshot factory rejection");
        });
        for (boolean packet : new boolean[] { false, true }) {
            NPC original = registry.createNPC(EntityType.COW, "SnapshotOriginal" + packet);
            NPC restored = registry.createNPC(EntityType.COW, "SnapshotRestored" + packet);
            if (packet) { original.getOrAddTrait(PacketNPC.class); restored.getOrAddTrait(PacketNPC.class); }
            try {
                var originalBehavior = original.getOrAddTrait(BehaviorTrait.class);
                DataKey initial = new MemoryDataKey(); initial.setString("root", "succeed");
                originalBehavior.applyBehaviorsFromKey(initial);
                originalBehavior.getMemory().set("snapshot_input", 7);
                originalBehavior.getMemory().set("snapshot_list", Arrays.asList("saved", null, "list"));
                DataKey definition = new MemoryDataKey(); definition.setString("root", "creation_snapshot_capture");
                originalBehavior.applyBehaviorsFromKey(definition);
                DataKey snapshot = new MemoryDataKey(); originalBehavior.save(snapshot);
                var behavior = restored.getOrAddTrait(BehaviorTrait.class);
                behavior.load(snapshot.copyWithRoot());
                check(restored.spawn(new Location(level, 30, -58, 4)), "snapshot_memory_spawn_" + packet);
                restored.getDefaultBehaviorController().run();
                check(behavior.getMemory().getNumber("factory_input") == 7
                        && behavior.getMemory().get("factory_list").equals(Arrays.asList("saved", null, "list")),
                        "snapshot_factory_sees_restored_scalar_and_list_" + packet);
                DataKey changed = snapshot.copyWithRoot(); changed.setInt("memory.snapshot_input", 11);
                behavior.load(changed);
                restored.getDefaultBehaviorController().run();
                check(behavior.getMemory().getNumber("factory_input") == 11,
                        "snapshot_factory_sees_incoming_not_previous_memory_" + packet);
                var previousMemory = behavior.getMemory();
                DataKey before = new MemoryDataKey(); behavior.save(before);
                DataKey rejected = snapshot.copyWithRoot(); rejected.setInt("memory.snapshot_input", 23);
                rejected.setString("tree.root", "creation_snapshot_reject");
                boolean failed = false;
                try { behavior.load(rejected); }
                catch (IllegalArgumentException expected) { failed = true; }
                check(failed && rejectedInput.get() == 23 && behavior.getMemory() == previousMemory
                        && behavior.getMemory().getNumber("snapshot_input") == 11
                        && !behavior.getMemory().has("rejected_factory_write"),
                        "rejected_snapshot_factory_preserves_live_memory_" + packet);
                int beforeRun = runs.get(); restored.getDefaultBehaviorController().run();
                check(runs.get() == beforeRun + 1 && behavior.getMemory().getNumber("factory_input") == 11,
                        "rejected_snapshot_preserves_live_tree_" + packet);
                DataKey after = new MemoryDataKey(); behavior.save(after);
                check(before.getValuesDeep().equals(after.getValuesDeep()), "rejected_snapshot_preserves_saved_state_" + packet);

                DataKey unreadable = new MemoryDataKey(snapshot.getValuesDeep()) {
                    @Override public MemoryDataKey getRelative(String relative) {
                        if (relative.equals("memory")) return new MemoryDataKey() {
                            @Override public MemoryDataKey copyWithRoot() { throw new IllegalStateException("expected memory read failure"); }
                        };
                        return super.getRelative(relative);
                    }
                };
                int beforeBuild = builds.get(); failed = false;
                try { behavior.load(unreadable); }
                catch (IllegalStateException expected) { failed = true; }
                check(failed && builds.get() == beforeBuild && behavior.getMemory() == previousMemory,
                        "unreadable_snapshot_memory_rejects_before_tree_construction_" + packet);
                DataKey afterUnreadable = new MemoryDataKey(); behavior.save(afterUnreadable);
                check(before.getValuesDeep().equals(afterUnreadable.getValuesDeep()),
                        "unreadable_snapshot_memory_preserves_saved_state_" + packet);

                DataKey memoryOnly = new MemoryDataKey(); memoryOnly.setRaw("memory", changed.getRaw("memory"));
                memoryOnly.setInt("memory.snapshot_input", 17);
                behavior.load(memoryOnly);
                check(behavior.getMemory() == previousMemory && builds.get() == beforeBuild
                        && previousMemory.getNumber("snapshot_input") == 17,
                        "memory_only_load_keeps_existing_behavior_context_" + packet);
                DataKey treeOnly = new MemoryDataKey(); treeOnly.setRaw("tree", snapshot.getRaw("tree"));
                behavior.load(treeOnly);
                restored.getDefaultBehaviorController().run();
                check(behavior.getMemory() == previousMemory && previousMemory.getNumber("factory_input") == 17,
                        "tree_only_load_uses_current_memory_" + packet);
                check(restored.despawn(DespawnReason.PENDING_RESPAWN) && restored.spawn(new Location(level, 30, -58, 4)),
                        "snapshot_memory_respawn_" + packet);
                restored.getDefaultBehaviorController().run();
                check(behavior.getMemory() == previousMemory && previousMemory.getNumber("factory_input") == 17,
                        "snapshot_factory_memory_survives_respawn_" + packet);

                DataKey empty = new MemoryDataKey(); empty.setRaw("tree", Map.of()); empty.setInt("memory.snapshot_input", 29);
                beforeRun = runs.get(); behavior.load(empty); restored.getDefaultBehaviorController().run();
                check(runs.get() == beforeRun && behavior.getMemory().getNumber("snapshot_input") == 29,
                        "empty_snapshot_tree_clears_execution_and_restores_memory_" + packet);
            } finally { original.destroy(); restored.destroy(); }
        }
    }

    private static void behaviorRootLifecycle() {
        var probes = new IdentityHashMap<NPC, RootProbe>();
        CitizensAPI.getBehaviorRegistry().registerBehavior("creation_root_lifecycle", (params, context) -> {
            RootProbe probe = probes.get(context.getNPC());
            probe.scope = context.getScope();
            return probe;
        });
        for (boolean packet : new boolean[] { false, true }) {
            for (String phase : List.of("success", "failure", "remove", "start_error", "run_error", "reset_error")) {
                NPC target = registry.createNPC(EntityType.COW, "RootLifecycle" + packet + phase);
                if (packet) target.getOrAddTrait(PacketNPC.class);
                RootProbe probe = new RootProbe(phase);
                probes.put(target, probe);
                try {
                    var behavior = target.getOrAddTrait(BehaviorTrait.class);
                    DataKey definition = new MemoryDataKey(); definition.setString("root", "creation_root_lifecycle");
                    behavior.applyBehaviorsFromKey(definition);
                    check(target.spawn(new Location(level, 28, -58, 4)), "root_lifecycle_spawn_" + phase + "_" + packet);
                    Throwable escaped = null;
                    try { target.getDefaultBehaviorController().run(); }
                    catch (RuntimeException | Error failure) { escaped = failure; }
                    check(phase.equals("reset_error") ? escaped == probe.error : escaped == null,
                            "root_lifecycle_failure_policy_" + phase + "_" + packet);
                    check(probe.starts == 1 && probe.runs == (phase.equals("start_error") ? 0 : 1) && probe.resets == 1,
                            "root_lifecycle_single_cleanup_" + phase + "_" + packet);
                    String previousName = target.getFullName();
                    target.setName("RootRenamed" + packet + phase);
                    target.getDefaultBehaviorController().run();
                    boolean repeats = phase.equals("success") || phase.equals("failure");
                    check(probe.starts == (repeats ? 2 : 1) && probe.resets == (repeats ? 2 : 1)
                            && probe.runs == (repeats ? 2 : phase.equals("start_error") ? 0 : 1),
                            "root_lifecycle_no_retry_after_error_or_removal_" + phase + "_" + packet);
                    if (repeats) {
                        check(probe.names.equals(List.of(previousName, target.getFullName())),
                                "root_lifecycle_admission_refreshes_scope_" + phase + "_" + packet);
                    }
                    RootProbe replacement = new RootProbe("success"); probes.put(target, replacement);
                    behavior.applyBehaviorsFromKey(definition);
                    target.getDefaultBehaviorController().run();
                    check(replacement.starts == 1 && replacement.runs == 1 && replacement.resets == 1,
                            "root_lifecycle_replacement_recovers_" + phase + "_" + packet);
                    check(target.despawn(DespawnReason.PENDING_RESPAWN) && target.spawn(new Location(level, 28, -58, 4)),
                            "root_lifecycle_respawns_" + phase + "_" + packet);
                    target.getDefaultBehaviorController().run();
                    check(replacement.starts == 2 && replacement.runs == 2 && replacement.resets == 2,
                            "root_lifecycle_respawn_cleanup_once_" + phase + "_" + packet);
                } finally {
                    target.destroy(); probes.remove(target);
                }
            }
        }
    }

    private static final class RootProbe implements net.citizensnpcs.api.ai.tree.Behavior {
        final IllegalStateException error;
        final List<String> names = new ArrayList<>();
        final String phase;
        net.citizensnpcs.api.expr.ExpressionScope scope;
        int starts, runs, resets;

        RootProbe(String phase) { this.phase = phase; error = new IllegalStateException("expected root lifecycle " + phase); }
        public boolean shouldExecute() {
            starts++;
            names.add(String.valueOf(scope.get("npc.name")));
            if (phase.equals("start_error")) throw error;
            return true;
        }
        public net.citizensnpcs.api.ai.tree.BehaviorStatus run() {
            runs++; scope.get("npc.name");
            if (phase.equals("run_error")) throw error;
            return switch (phase) {
                case "failure" -> net.citizensnpcs.api.ai.tree.BehaviorStatus.FAILURE;
                case "remove" -> net.citizensnpcs.api.ai.tree.BehaviorStatus.RESET_AND_REMOVE;
                default -> net.citizensnpcs.api.ai.tree.BehaviorStatus.SUCCESS;
            };
        }
        public void reset() {
            resets++;
            if (phase.equals("reset_error")) throw error;
        }
    }

    private static void mappedBehaviorTicks() {
        CitizensAPI.getBehaviorRegistry().registerBehavior("creation_mapping", (params, context) -> {
            var result = net.citizensnpcs.api.ai.tree.BehaviorStatus.valueOf(
                    context.getArgOrParam(0, "status", params, "SUCCESS"));
            var children = context.parseChildren(params);
            var child = children.size() == 1 ? children.get(0)
                    : net.citizensnpcs.api.ai.tree.Sequence.createSequence(children);
            return net.citizensnpcs.api.ai.tree.StatusMapper.mapping(child, () -> result);
        });
        for (boolean packet : new boolean[] { false, true }) {
            NPC target = registry.createNPC(EntityType.COW, "MappedBehavior" + packet);
            if (packet) target.getOrAddTrait(PacketNPC.class);
            var behavior = target.getOrAddTrait(BehaviorTrait.class);
            DataKey definition = new MemoryDataKey();
            definition.setRaw("sequence", Map.of(
                    "0", Map.of("repeat 3", Map.of(
                            "0", Map.of("invert", Map.of("0", Map.of("creation_mapping FAILURE",
                                    Map.of("0", "set mapped_executions `mem.get('mapped_executions') + 1`")))),
                            "1", "wait 2")),
                    "1", Map.of("creation_mapping SUCCESS", Map.of("0", "set mapped_effect `1`", "1", "fail")),
                    "2", "set mapped_finished `1`",
                    "3", "wait_for_signal mapped_audit_hold"));
            behavior.applyBehaviorsFromKey(definition);
            behavior.getMemory().set("mapped_executions", 0D);
            check(target.spawn(new Location(level, 24, -58, 6)), "mapped_behavior_spawn_" + packet);
            atTick(2, () -> check(behavior.getMemory().getNumber("mapped_executions") < 3
                    && behavior.getMemory().getNumber("mapped_finished") == 0,
                    "mapped_behavior_sequence_preserves_waits_" + packet));
            atTick(30, () -> {
                check(behavior.getMemory().getNumber("mapped_executions") == 3,
                        "mapped_failure_runs_successful_child_on_each_attempt_" + packet);
                check(behavior.getMemory().getNumber("mapped_effect") == 1
                        && behavior.getMemory().getNumber("mapped_finished") == 1,
                        "mapped_success_keeps_failing_child_effects_and_advances_" + packet);
            });
            atTick(33, () -> {
                check(behavior.getMemory().getNumber("mapped_executions") == 3
                        && behavior.getMemory().getNumber("mapped_effect") == 1,
                        "mapped_behavior_sequence_stays_at_signal_wait_" + packet);
                target.destroy();
            });
        }
    }

    private static void globalCommandCooldown() throws Exception {
        cooldownPlayer = actor("CooldownBob");
        var bob = cooldownPlayer.player;
        check(!PermissionUtil.hasPermission(alice.player, "citizens.npc.command.ignoreerrors.globalcooldown")
                && !PermissionUtil.hasPermission(bob, "citizens.npc.command.ignoreerrors.globalcooldown"),
                "global_cooldown_players_have_no_bypass");
        for (boolean packet : new boolean[] { false, true }) {
            var rewards = new java.util.concurrent.atomic.AtomicInteger();
            String reward = "creation_global_cooldown_" + packet;
            server.getCommands().getDispatcher().register(net.minecraft.commands.Commands.literal(reward)
                    .executes(ctx -> rewards.incrementAndGet()));
            NPC target = registry.createNPC(EntityType.COW, "GlobalCooldown" + packet);
            NPC independent = registry.createNPC(EntityType.COW, "IndependentCooldown" + packet);
            if (packet) { target.getOrAddTrait(PacketNPC.class); independent.getOrAddTrait(PacketNPC.class); }
            check(target.spawn(new Location(level, 23, -58, 4)) && independent.spawn(new Location(level, 23, -58, 6)),
                    "global_cooldown_npcs_spawn_" + packet);
            check(server.getCommands().getDispatcher().execute("npc command add " + reward
                    + " --gcooldown 1h --expcost 2 --id " + target.getId(), server.createCommandSourceStack()) == 1,
                    "global_cooldown_command_add_" + packet);
            var commands = target.getOrAddTrait(CommandTrait.class);
            independent.getOrAddTrait(CommandTrait.class).addCommand(new CommandTrait.NPCCommandBuilder(reward,
                    CommandTrait.Hand.RIGHT).globalCooldown(3600).experienceCost(2));
            alice.player.setExperienceLevels(20); bob.setExperienceLevels(20);
            commands.dispatch(alice.player, CommandTrait.Hand.RIGHT);
            check(alice.player.experienceLevel == 18, "global_cooldown_first_command_charges_once_" + packet);
            DataKey saved = new MemoryDataKey(); net.citizensnpcs.api.persistence.PersistenceLoader.save(commands, saved);
            commands.dispatch(bob, CommandTrait.Hand.RIGHT);
            int blockedLevels = bob.experienceLevel;
            CommandTrait[] current = { commands };
            java.util.function.Function<DataKey, CommandTrait> restore = snapshot -> {
                var restored = new CommandTrait();
                net.citizensnpcs.api.persistence.PersistenceLoader.loadStrict(restored, snapshot);
                target.addTrait(restored);
                return restored;
            };
            atTick(5, () -> {
                check(rewards.get() == 1, "global_cooldown_blocks_another_players_reward_" + packet);
                check(blockedLevels == 20, "global_cooldown_blocks_per_command_charge_" + packet);
                check(saved.getInt("commands.0.globalcooldown") == 3600 && saved.getRelative("globalCooldowns").hasSubKeys(),
                        "global_cooldown_persists_duration_and_use_time_" + packet);
                current[0] = restore.apply(saved.copyWithRoot());
                int before = bob.experienceLevel; current[0].dispatch(bob, CommandTrait.Hand.RIGHT);
                check(bob.experienceLevel == before, "global_cooldown_restored_state_still_blocks_" + packet);
                DataKey expired = saved.copyWithRoot();
                for (DataKey time : expired.getRelative("globalCooldowns").getSubKeys())
                    time.setLong("", System.currentTimeMillis() / 1000 - 3601);
                current[0] = restore.apply(expired);
                current[0].dispatch(bob, CommandTrait.Hand.RIGHT);
                check(bob.experienceLevel == before - 2, "global_cooldown_expired_state_allows_payment_" + packet);
            });
            atTick(8, () -> {
                check(rewards.get() == 2, "global_cooldown_expiry_dispatches_one_reward_" + packet);
                current[0].clearHistory(CommandTrait.CommandTraitError.ON_GLOBAL_COOLDOWN, null);
                current[0].clearHistory(CommandTrait.CommandTraitError.ON_COOLDOWN, bob.getUUID());
                current[0].dispatch(bob, CommandTrait.Hand.RIGHT);
            });
            atTick(10, () -> {
                check(rewards.get() == 3, "global_cooldown_clear_allows_next_command_" + packet);
                var bypass = PermissionUtil.grantTemporary(bob, List.of("citizens.npc.command.ignoreerrors.globalcooldown"));
                try {
                    current[0].clearHistory(CommandTrait.CommandTraitError.ON_COOLDOWN, bob.getUUID());
                    current[0].dispatch(bob, CommandTrait.Hand.RIGHT);
                } finally { bypass.remove(); }
            });
            atTick(12, () -> {
                check(rewards.get() == 4, "global_cooldown_permission_bypasses_global_gate_" + packet);
                independent.getOrAddTrait(CommandTrait.class).dispatch(bob, CommandTrait.Hand.RIGHT);
            });
            atTick(15, () -> {
                check(rewards.get() == 5, "global_cooldown_does_not_block_another_npc_" + packet);
                target.destroy(); independent.destroy();
            });
        }
    }

    private static void timerBehaviorTicks() {
        CitizensAPI.getBehaviorRegistry().registerBehavior("creation_timer", (params, context) -> {
            int budget = Integer.parseInt(context.getArgOrParam(0, "limit", params, "1"));
            var children = context.parseChildren(params);
            var child = children.size() == 1 ? children.get(0)
                    : net.citizensnpcs.api.ai.tree.Sequence.createSequence(children);
            return net.citizensnpcs.api.ai.tree.TimerDecorator.tickLimiter(child, budget);
        });
        for (boolean packet : new boolean[] { false, true }) {
            NPC target = registry.createNPC(EntityType.COW, "TimerBehavior" + packet);
            if (packet) target.getOrAddTrait(PacketNPC.class);
            var behavior = target.getOrAddTrait(BehaviorTrait.class);
            DataKey definition = new MemoryDataKey();
            definition.setRaw("sequence", Map.of(
                    "0", Map.of("creation_timer 1", Map.of("0", "set first_tick `1`")),
                    "1", Map.of("repeat 3", Map.of(
                            "0", Map.of("creation_timer 2", Map.of("0", "wait 2")),
                            "1", "set timer_iterations `mem.get('timer_iterations') + 1`")),
                    "2", Map.of("invert", Map.of("0", Map.of("creation_timer 0", Map.of("0", "set zero_tick `1`")))),
                    "3", Map.of("invert", Map.of("0", Map.of("creation_timer 1", Map.of("0", "wait 2")))),
                    "4", "set timer_finished `1`",
                    "5", "wait_for_signal timer_audit_hold"));
            behavior.applyBehaviorsFromKey(definition);
            behavior.getMemory().set("timer_iterations", 0D);
            check(target.spawn(new Location(level, 25, -58, 4)), "timer_behavior_spawn_" + packet);
            atTick(2, () -> check(behavior.getMemory().getNumber("timer_finished") == 0
                    && behavior.getMemory().getNumber("timer_iterations") < 3,
                    "timer_wait_yields_across_ticks_" + packet));
            atTick(35, () -> {
                check(behavior.getMemory().getNumber("first_tick") == 1,
                        "timer_accepts_single_tick_child_" + packet);
                check(behavior.getMemory().getNumber("timer_iterations") == 3,
                        "timer_reactivation_completes_three_boundary_waits_" + packet);
                check(behavior.getMemory().getNumber("zero_tick") == 0
                        && behavior.getMemory().getNumber("timer_finished") == 1,
                        "timer_expiration_and_zero_budget_allow_only_successors_" + packet);
            });
            atTick(38, () -> {
                check(behavior.getMemory().getNumber("timer_iterations") == 3
                        && behavior.getMemory().getNumber("timer_finished") == 1,
                        "timer_completed_sequence_stays_stopped_" + packet);
                target.destroy();
            });
        }
    }

    private static void retryBehaviorTicks() {
        // Exercise the public decorator through a provider factory; this does not add a production YAML node.
        CitizensAPI.getBehaviorRegistry().registerBehavior("creation_retry_three", (params, context) ->
                net.citizensnpcs.api.ai.tree.RetryDecorator.retry(
                        net.citizensnpcs.api.ai.tree.Sequence.createSequence(context.parseChildren(params)), 3));
        for (boolean packet : new boolean[] { false, true }) {
            NPC target = registry.createNPC(EntityType.COW, "RetryBehavior" + packet);
            if (packet) target.getOrAddTrait(PacketNPC.class);
            var behavior = target.getOrAddTrait(BehaviorTrait.class);
            DataKey definition = new MemoryDataKey();
            definition.setRaw("sequence", Map.of(
                    "0", Map.of("invert", Map.of("0", Map.of("creation_retry_three", Map.of(
                            "0", "wait 2", "1", "set failed_attempts `mem.get('failed_attempts') + 1`", "2", "fail")))),
                    "1", Map.of("creation_retry_three", Map.of(
                            "0", "wait 2", "1", "set recovered_attempts `mem.get('recovered_attempts') + 1`",
                            "2", Map.of("if `mem.get('recovered_attempts') >= 3`", "succeed", "else", "fail"))),
                    "2", "set retry_finished `1`",
                    "3", "wait_for_signal retry_audit_hold"));
            behavior.applyBehaviorsFromKey(definition);
            behavior.getMemory().set("failed_attempts", 0D);
            behavior.getMemory().set("recovered_attempts", 0D);
            check(target.spawn(new Location(level, 24, -58, 4)), "retry_behavior_spawn_" + packet);
            atTick(2, () -> check(behavior.getMemory().getNumber("retry_finished") == 0
                    && behavior.getMemory().getNumber("failed_attempts") < 3,
                    "retry_wait_yields_across_ticks_" + packet));
            atTick(60, () -> {
                check(behavior.getMemory().getNumber("failed_attempts") == 3,
                        "retry_stops_at_configured_failure_budget_" + packet);
                check(behavior.getMemory().getNumber("recovered_attempts") == 3
                        && behavior.getMemory().getNumber("retry_finished") == 1,
                        "retry_stateful_child_recovers_on_last_attempt_" + packet);
            });
            atTick(63, () -> {
                check(behavior.getMemory().getNumber("failed_attempts") == 3
                        && behavior.getMemory().getNumber("recovered_attempts") == 3,
                        "retry_terminal_children_stay_stopped_" + packet);
                target.destroy();
            });
        }
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
                    "2", Map.of("if false", "set branch_wrong `1`", "else", "set branch_selected `1`"),
                    "3", Map.of("timeout 5", Map.of("0", "wait `2`", "1", "set timed_finished `1`")),
                    "4", "cooldown audit_gate 100000d",
                    "5", Map.of("invert", Map.of("0", "cooldown audit_gate 100000d")),
                    "6", "set repeat_finished `1`",
                    "7", "wait_for_signal repeat_audit_hold",
                    "8", "set signal_finished `1`",
                    "9", "wait_for_signal repeat_audit_final_hold"));
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
                check(behavior.getMemory().getNumber("branch_wrong") == 0
                        && behavior.getMemory().getNumber("branch_selected") == 1
                        && behavior.getMemory().getNumber("timed_finished") == 1
                        && behavior.getMemory().getNumber("cooldown.audit_gate") > 0,
                        "native_conditional_timeout_cooldown_chain_completes_" + packet);
                CitizensAPI.getBehaviorRegistry().getSignalManager().emit(target, "repeat_audit_hold");
            });
            atTick(23, () -> {
                check(behavior.getMemory().getNumber("signal_finished") == 1,
                        "native_signal_resumes_waiting_tree_" + packet);
                target.destroy();
            });
        }
    }

    private static void conditionalContainerTicks() {
        for (boolean packet : new boolean[] { false, true }) {
            NPC target = registry.createNPC(EntityType.COW, "ConditionalContainers" + packet);
            if (packet) target.getOrAddTrait(PacketNPC.class);
            var behavior = target.getOrAddTrait(BehaviorTrait.class);
            DataKey definition = new MemoryDataKey();
            definition.setRaw("sequence", Map.of(
                    "0", Map.of("invert", Map.of("0", Map.of("timeout 0",
                            Map.of("if true", "set timeout_wrong `1`")))),
                    "1", Map.of("invert", Map.of("if false", "succeed", "else", "fail")),
                    "2", Map.of("invert", Map.of("0", Map.of("repeat", Map.of("count", 4, "if true", Map.of(
                            "0", "set budget `4 - mem.get('repetitions')`",
                            "1", Map.of("timeout `mem.get('budget')`", Map.of("0", "wait 2")),
                            "2", "set repetitions `mem.get('repetitions') + 1`"))))),
                    "3", Map.of("loop `mem.get('loops') < 3`", Map.of("if true", Map.of(
                            "0", "wait 2", "1", "set loops `mem.get('loops') + 1`"))),
                    "4", "set containers_finished `1`",
                    "5", "wait_for_signal conditional_containers_hold"));
            behavior.applyBehaviorsFromKey(definition);
            behavior.getMemory().set("repetitions", 0D);
            behavior.getMemory().set("loops", 0D);
            check(target.spawn(new Location(level, 26, -58, 4)), "conditional_containers_spawn_" + packet);
            atTick(2, () -> check(behavior.getMemory().getNumber("containers_finished") == 0,
                    "conditional_containers_yield_across_ticks_" + packet));
            atTick(40, () -> {
                check(behavior.getMemory().getNumber("repetitions") == 3
                        && behavior.getMemory().getNumber("budget") == 1,
                        "dynamic_timeout_rechecks_budget_and_stops_fourth_iteration_" + packet);
                check(behavior.getMemory().getNumber("loops") == 3,
                        "direct_conditional_loop_restarts_waiting_body_" + packet);
                check(behavior.getMemory().getNumber("timeout_wrong") == 0
                        && behavior.getMemory().getNumber("containers_finished") == 1,
                        "direct_conditional_timeout_and_invert_keep_outer_control_" + packet);
            });
            atTick(44, () -> {
                check(behavior.getMemory().getNumber("repetitions") == 3
                        && behavior.getMemory().getNumber("loops") == 3
                        && behavior.getMemory().getNumber("containers_finished") == 1,
                        "completed_conditional_containers_do_not_restart_" + packet);
                target.destroy();
            });
        }
    }

    private static void scalarBehaviorTicks() {
        for (boolean packet : new boolean[]{false, true}) {
            NPC target = registry.createNPC(EntityType.COW, "ScalarBehavior" + packet);
            if (packet) target.getOrAddTrait(PacketNPC.class);
            var behavior = target.getOrAddTrait(BehaviorTrait.class);
            DataKey definition = new MemoryDataKey();
            definition.setRaw("sequence", Map.of("0", Map.of("wait", 0), "1", Map.of("wait_until", false),
                    "2", "set scalar_finished `1`", "3", "wait_for_signal scalar_audit_hold"));
            behavior.applyBehaviorsFromKey(definition);
            check(target.spawn(new Location(level, 24, -58, 4)), "scalar_behavior_spawn_" + packet);
            atTick(10, () -> check(behavior.getMemory().getNumber("scalar_finished") == 0,
                    "scalar_false_condition_keeps_native_tree_waiting_" + packet));
            atTick(20, () -> {
                DataKey replacement = new MemoryDataKey();
                replacement.setRaw("sequence", Map.of("0", Map.of("wait", 0), "1", Map.of("wait_until", true),
                        "2", "set scalar_finished `1`", "3", "wait_for_signal scalar_audit_hold"));
                behavior.applyBehaviorsFromKey(replacement);
            });
            atTick(24, () -> {
                check(behavior.getMemory().getNumber("scalar_finished") == 1,
                        "scalar_zero_wait_and_true_condition_advance_native_tree_" + packet);
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
            for (Actor actor : new Actor[] { alice, cooldownPlayer }) {
                if (actor != null) { server.getPlayerList().remove(actor.player); actor.channel.finishAndReleaseAll(); }
            }
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
