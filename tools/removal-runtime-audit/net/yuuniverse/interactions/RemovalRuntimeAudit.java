package net.yuuniverse.interactions;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.event.NPCRemoveByCommandSenderEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.persistence.LocationPersister;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitInfo;
import net.citizensnpcs.api.trait.trait.CurrentLocation;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.ItemStorage;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.commands.NPCCommandSelector;
import net.citizensnpcs.trait.SneakTrait;
import net.citizensnpcs.trait.CommandTrait;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.InventoryViewer;
import net.citizensnpcs.trait.shop.NPCShopStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class RemovalRuntimeAudit {
    private static State state;
    private static boolean forced, finished;
    private static int passed, step, deadline;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        var server = event.getServer();
        var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        try {
            if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, 0, 1))) {
                if (server.getTickCount() > 1200) throw new AssertionError("Fixture loading timed out");
                return;
            }
            if (state == null) { state = new State(server); state.initialize(); deadline = server.getTickCount() + 100; }
            if (server.getTickCount() > deadline) throw new AssertionError("Step " + step + " timed out");
            if (state.steps.get(step).run()) {
                step++;
                deadline = server.getTickCount() + 100;
            }
            if (step == state.steps.size()) {
                LoggerFactory.getLogger("citizens").info("[REMOVALAUDIT] COMPLETE {} checks", passed);
                finish(server);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[REMOVALAUDIT] FAILED", failure);
            finish(server);
        }
    }

    private static void finish(MinecraftServer server) {
        finished = true;
        if (state != null) try { state.close(); }
        catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[REMOVALAUDIT] FAILED cleanup", failure); }
        server.halt(false);
    }

    @FunctionalInterface private interface Step { boolean run() throws Exception; }
    @FunctionalInterface private interface Action { void run() throws Exception; }

    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final NPCRegistry registry = CitizensAPI.getNPCRegistry();
        final List<Step> steps = new ArrayList<>();
        final List<AuditPlayer> players = new ArrayList<>();
        final List<PermissionUtil.Attachment> grants = new ArrayList<>();
        final AtomicReference<NPC> chosen = new AtomicReference<>();
        final AtomicInteger recoveredItemRewards = new AtomicInteger();
        final RemovalRecorder recorder = new RemovalRecorder();
        final TraitInfo brokenInfo = TraitInfo.create(BrokenSnapshot.class).withName("removalauditfailure");
        boolean isolated;
        NPCRegistry named;
        AuditPlayer alice, bob;
        CommandSourceStack a, b, console;
        PermissionUtil.Attachment removeGrant;
        NPC duplicateOne, duplicateTwo, replacement, selectionControl;
        UUID spawnedRestore;
        int beforeMessages;

        State(MinecraftServer server) { this.server = server; level = server.overworld(); }

        void initialize() throws Exception {
            if (registry.iterator().hasNext()) throw new AssertionError("This fixture must start without NPC data");
            isolated = true;
            named = CitizensAPI.createInMemoryNPCRegistry("removal-audit-named");
            alice = player("RemovalAlice"); bob = player("RemovalBob");
            a = alice.createCommandSourceStack().withPermission(0);
            b = bob.createCommandSourceStack().withPermission(0);
            console = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
            check(!PermissionUtil.hasPermission(a, "citizens.admin") && !PermissionUtil.hasPermission(b, "citizens.admin"),
                    "unprivileged_player_fixtures");
            removeGrant = grant(alice, "citizens.npc.remove");
            grant(alice, "citizens.npc.undo"); grant(bob, "citizens.npc.undo");
            NeoForge.EVENT_BUS.register(recorder);
            CitizensAPI.getTraitFactory().registerTrait(brokenInfo);
            add(this::basicCommands);
            steps.add(() -> {
                NPC restored = registry.getByUniqueId(spawnedRestore);
                if (restored == null || !restored.isSpawned()) return false;
                if (recoveredItemRewards.get() < 2) return false;
                check(recoveredItemRewards.get() == 2, "only_repaired_item_costs_dispatch_scheduled_rewards");
                check(restored.getEntity().isShiftKeyDown()
                        && Math.abs(restored.getNavigator().getDefaultParameters().speedModifier() - 1.75F) < 0.0001,
                        "undo_restores_spawned_entity_traits_and_navigation");
                return true;
            });
            add(() -> {
                duplicateOne = npc(registry, alice.getUUID(), "Duplicate", null);
                duplicateTwo = npc(registry, alice.getUUID(), "Duplicate", null);
                selectionControl = npc(registry, alice.getUUID(), "SelectionControl", null);
                CitizensAPI.getDefaultNPCSelector().select(a, selectionControl);
                ok(a, "npc remove Duplicate");
                check(ChatPrompts.isActive(alice) && exists(duplicateOne) && exists(duplicateTwo), "ambiguous_player_name_opens_prompt");
                beforeMessages = alice.messages.size(); chat("999999999");
            });
            steps.add(() -> {
                if (alice.messages.size() == beforeMessages) return false;
                check(ChatPrompts.isActive(alice) && exists(duplicateOne) && exists(duplicateTwo), "invalid_prompt_choice_preserves_all_candidates");
                chat("exit"); return true;
            });
            steps.add(() -> {
                if (ChatPrompts.isActive(alice)) return false;
                check(exists(duplicateOne) && exists(duplicateTwo), "exit_cancels_ambiguous_removal");
                ok(a, "npc remove Duplicate"); chat(Integer.toString(duplicateTwo.getId())); return true;
            });
            steps.add(() -> {
                if (ChatPrompts.isActive(alice)) return false;
                check(exists(duplicateOne) && !exists(duplicateTwo)
                        && CitizensAPI.getDefaultNPCSelector().getSelected(a) == selectionControl,
                        "prompt_removes_only_chosen_npc_and_preserves_selection");
                ok(a, "npc undo"); duplicateTwo = registry.getByUniqueId(duplicateTwo.getUniqueId());
                ok(a, "npc remove Duplicate");
                int reusedId = duplicateTwo.getId(); duplicateTwo.destroy();
                replacement = registry.createNPC(EntityType.PIG, UUID.randomUUID(), reusedId, "Replacement");
                configure(replacement, alice.getUUID(), null);
                beforeMessages = alice.messages.size(); chat(Integer.toString(reusedId)); return true;
            });
            steps.add(() -> {
                if (alice.messages.size() == beforeMessages) return false;
                check(ChatPrompts.isActive(alice) && exists(replacement) && exists(duplicateOne), "stale_prompt_id_cannot_delete_replacement");
                chat("exit"); return true;
            });
            steps.add(() -> {
                if (ChatPrompts.isActive(alice)) return false;
                duplicateTwo = npc(registry, alice.getUUID(), "Duplicate", null);
                ok(a, "npc remove Duplicate"); duplicateTwo.getOrAddTrait(Owner.class).setOwner(bob.getUUID());
                chat(Integer.toString(duplicateTwo.getId())); return true;
            });
            steps.add(() -> {
                if (ChatPrompts.isActive(alice)) return false;
                check(exists(duplicateTwo), "ownership_is_rechecked_after_prompt");
                duplicateTwo.getOrAddTrait(Owner.class).setOwner(alice.getUUID());
                ok(a, "npc remove Duplicate"); removeGrant.remove();
                chat(Integer.toString(duplicateTwo.getId())); return true;
            });
            steps.add(() -> {
                if (ChatPrompts.isActive(alice)) return false;
                check(exists(duplicateTwo), "permission_is_rechecked_after_prompt");
                removeGrant = grant(alice, "citizens.npc.remove");
                NPC shadow = npc(registry, alice.getUUID(), "NamedIdShadow", null);
                NPC first = named.createNPC(EntityType.PIG, UUID.randomUUID(), shadow.getId(), "NamedDuplicate");
                configure(first, alice.getUUID(), null);
                npc(named, alice.getUUID(), "NamedDuplicate", null);
                chosen.set(null);
                NPCCommandSelector.startWithCallback(chosen::set, named, a,
                        new CommandContext(a, new String[] { "npc", "remove", "NamedDuplicate" }), "NamedDuplicate");
                chat(Integer.toString(first.getId()));
                return true;
            });
            steps.add(() -> {
                if (ChatPrompts.isActive(alice)) return false;
                check(chosen.get() != null && chosen.get().getOwningRegistry() == named, "prompt_resolves_in_the_candidates_registry");
                return true;
            });
        }

        void basicCommands() throws Exception {
            NPC control = npc(registry, alice.getUUID(), "Control", null);
            NPC target = npc(registry, alice.getUUID(), "Target", null);
            UUID targetUuid = target.getUniqueId(); int targetId = target.getId();
            CitizensAPI.getDefaultNPCSelector().select(a, control);
            ok(a, "npc remove " + targetId);
            check(exists(control) && !exists(target) && CitizensAPI.getDefaultNPCSelector().getSelected(a) == control,
                    "id_argument_does_not_delete_selected_npc");
            check(recorder.lastNpc == target && recorder.lastSource.getEntity() == alice, "removal_event_carries_command_sender");
            ok(a, "npc undo"); target = registry.getById(targetId);
            check(target != null && target.getUniqueId().equals(targetUuid) && target.getName().equals("Target")
                    && target.getOrAddTrait(Owner.class).isOwnedBy(alice.getUUID()), "undo_restores_identity_name_and_owner");
            CitizensAPI.getDefaultNPCSelector().select(a, control);
            ok(a, "npc rem tArGeT"); check(!exists(target) && exists(control), "name_and_rem_alias_resolve_target");
            ok(a, "npc undo"); target = registry.getByUniqueId(targetUuid);
            reject(a, "npc remove MissingTarget"); reject(a, "npc remove 999999999");
            check(exists(target) && exists(control), "missing_targets_preserve_existing_npcs");
            NPC bobNpc = npc(registry, bob.getUUID(), "BobsNpc", null);
            reject(a, "npc remove " + bobNpc.getId());
            reject(b, "npc remove " + bobNpc.getId());
            check(exists(bobNpc), "ownership_and_removal_permission_are_independent");
            NPC duplicateA = npc(registry, alice.getUUID(), "ConsoleDuplicate", null);
            NPC duplicateB = npc(registry, alice.getUUID(), "ConsoleDuplicate", null);
            reject(console, "npc remove ConsoleDuplicate");
            check(exists(duplicateA) && exists(duplicateB), "console_ambiguity_never_chooses_arbitrarily");

            NPC namedTarget = named.createNPC(EntityType.PIG, UUID.randomUUID(), control.getId(), "NamedTarget");
            configure(namedTarget, alice.getUUID(), null);
            UUID namedUuid = namedTarget.getUniqueId();
            ok(a, "npc remove " + namedUuid);
            check(!exists(namedTarget) && exists(control), "uuid_can_target_a_named_registry");
            ok(a, "npc undo");
            check(named.getByUniqueId(namedUuid) != null && registry.getById(control.getId()) == control,
                    "undo_returns_to_original_registry_without_overwriting_same_id");

            target = registry.getByUniqueId(targetUuid);
            ok(a, "npc remove " + targetId);
            NPC collision = registry.createNPC(EntityType.PIG, UUID.randomUUID(), targetId, "Collision");
            configure(collision, alice.getUUID(), null);
            reject(a, "npc undo");
            check(registry.getById(targetId) == collision && registry.getByUniqueId(targetUuid) == null, "undo_conflict_preserves_existing_npc");
            collision.destroy(); ok(a, "npc undo");
            check(registry.getByUniqueId(targetUuid) != null, "failed_undo_keeps_history_for_retry");
            ok(a, "npc remove " + targetId);
            NPC reservation = npc(registry, alice.getUUID(), "IdReservation", null);
            int otherId = reservation.getId(); reservation.destroy();
            NPC uuidCollision = registry.createNPC(EntityType.PIG, targetUuid, otherId, "UuidCollision");
            configure(uuidCollision, alice.getUUID(), null);
            reject(a, "npc undo");
            check(registry.getById(otherId) == uuidCollision && registry.getById(targetId) == null,
                    "undo_uuid_conflict_preserves_existing_npc");
            uuidCollision.destroy(); ok(a, "npc undo");

            NPC eidNpc = npc(registry, alice.getUUID(), "EntityTarget", level);
            eidNpc.getOrAddTrait(Spawned.class).setSpawned(true);
            if (!eidNpc.spawn(new Location(level, 1, -60, 1))) throw new AssertionError("Entity fixture spawn failed");
            ok(a, "npc remove --eid " + eidNpc.getEntity().getUUID());
            check(!exists(eidNpc) && exists(control), "entity_uuid_removes_its_npc_only");
            reject(a, "npc remove --eid " + UUID.randomUUID());
            ok(a, "npc undo");

            ok(a, "npc remove --owner " + alice.getUUID());
            check(!exists(control) && exists(bobNpc), "owner_filter_respects_sender_ownership");
            ok(a, "npc undo all");
            control = registry.getByUniqueId(control.getUniqueId());
            UUID cachedOwner = UUID.randomUUID();
            server.getProfileCache().add(new GameProfile(cachedOwner, "CachedOwner"));
            NPC cached = npc(registry, cachedOwner, "CachedOwned", null);
            ok(console, "npc remove --owner CachedOwner");
            check(!exists(cached) && exists(control), "owner_filter_resolves_cached_offline_name");
            ok(console, "npc undo");
            NPC serverOwned = npc(registry, null, "ServerOwned", null);
            ok(console, "npc remove --owner server");
            check(!exists(serverOwned) && exists(control), "server_owner_filter_is_explicit");
            ok(console, "npc undo");
            ok(b, "npc remove --owner " + bob.getUUID());
            check(!exists(bobNpc), "owner_filtered_removal_matches_upstream_owner_authority");
            ok(b, "npc undo");

            NPC over = npc(registry, alice.getUUID(), "WorldOver", level);
            NPC end = npc(registry, alice.getUUID(), "WorldEnd", server.getLevel(Level.END));
            NPC foreign = npc(registry, bob.getUUID(), "ForeignWorldOver", level);
            reject(a, "npc remove --world misspelled_world");
            reject(a, "npc remove --world missing:overworld");
            check(exists(over) && exists(end) && exists(foreign), "unknown_world_has_no_overworld_fallback");
            ok(a, "npc remove --world minecraft:overworld");
            check(!exists(over) && exists(end) && exists(foreign), "world_filter_limits_dimension_and_ownership");
            ok(a, "npc undo all");
            String folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
            check(LocationPersister.resolveStrict(folder) == level
                    && LocationPersister.resolveStrict(folder + "/DIM1") == server.getLevel(Level.END),
                    "strict_world_names_use_the_actual_save_folder");
            UUID worldUuid = UUID.randomUUID();
            writeUid(server.getLevel(Level.END), worldUuid);
            ok(a, "npc remove --world " + worldUuid);
            check(!exists(end) && exists(foreign), "world_filter_accepts_existing_bukkit_uid");
            ok(a, "npc undo all");
            writeUid(level, worldUuid);
            reject(a, "npc remove --world " + worldUuid);
            check(registry.getByUniqueId(end.getUniqueId()) != null, "ambiguous_world_uuid_is_not_a_target");

            NPC temporary = npc(registry, alice.getUUID(), "Temporary", null);
            temporary.data().setPersistent(NPC.Metadata.SHOULD_SAVE, false);
            temporary.data().setPersistent("snapshot-marker", "retained");
            temporary.setSneaking(true);
            temporary.getNavigator().getDefaultParameters().speedModifier(1.75F);
            var disk = new MemoryDataKey(); temporary.save(disk);
            check(!disk.keyExists("name") && !disk.keyExists("navigator"), "temporary_npcs_still_skip_persistent_save");
            NPC copy = temporary.copy();
            check(copy.getName().equals("Temporary") && copy.getTraitNullable(SneakTrait.class).isSneaking()
                    && !copy.data().get(NPC.Metadata.SHOULD_SAVE, true), "temporary_copy_uses_complete_snapshot");
            UUID temporaryId = temporary.getUniqueId();
            ok(a, "npc remove " + temporary.getId()); ok(a, "npc undo");
            NPC restored = registry.getByUniqueId(temporaryId);
            check(restored != null && restored.getTraitNullable(SneakTrait.class).isSneaking()
                    && restored.data().get("snapshot-marker", "").equals("retained")
                    && !restored.data().get(NPC.Metadata.SHOULD_SAVE, true), "temporary_removal_can_be_undone_without_changing_save_policy");

            NPC pending = npc(registry, alice.getUUID(), "PendingTraitRemoval", null);
            pending.setSneaking(true);
            var existing = new MemoryDataKey(); pending.save(existing);
            pending.removeTrait(SneakTrait.class); pending.saveSnapshot(new MemoryDataKey()); pending.save(existing);
            check(!existing.keyExists("traits.sneak") && !existing.getString("traitnames").contains("sneak"),
                    "snapshot_does_not_consume_pending_persistent_trait_removals");
            NPC broken = npc(registry, alice.getUUID(), "BrokenSnapshot", null);
            broken.addTrait(BrokenSnapshot.class);
            reject(a, "npc remove " + broken.getId());
            check(exists(broken), "failed_snapshot_prevents_destruction");
            broken.removeTrait(BrokenSnapshot.class);

            NPC itemOwner = npc(registry, alice.getUUID(), "ItemSnapshot", null);
            var inventory = itemOwner.getOrAddTrait(Inventory.class);
            ItemStack validItem = ItemStorage.parseItemStack("minecraft:diamond_sword[enchantments={levels:{'minecraft:sharpness':3}}]", 1);
            if (validItem.isEmpty()) throw new AssertionError("Native enchantment fixture failed to parse");
            validItem.set(DataComponents.CUSTOM_NAME, Component.literal("Snapshot sword"));
            inventory.setContents(new ItemStack[] {validItem});
            var inventoryKey = new MemoryDataKey();
            inventory.save(inventoryKey);
            check(ItemStack.matches(validItem, ItemStorage.loadItemStack(inventoryKey.getRelative("0"))),
                    "native_enchantment_components_round_trip_with_live_registries");
            Object savedInventory = inventoryKey.copy().getRaw("");
            ItemStack invalidItem = new ItemStack(Items.DIAMOND_SWORD);
            invalidItem.set(DataComponents.DAMAGE, -1);
            inventory.setContents(new ItemStack[] {invalidItem});
            boolean failed = false;
            try { inventory.save(inventoryKey); } catch (IllegalStateException expected) { failed = true; }
            check(failed && savedInventory.equals(inventoryKey.getRaw("")), "failed_inventory_encode_keeps_previous_slot_record");
            reject(a, "npc remove " + itemOwner.getId());
            check(exists(itemOwner), "invalid_item_encoding_prevents_npc_destruction");
            boolean copyFailed = false;
            try { itemOwner.copy(); } catch (IllegalStateException expected) { copyFailed = true; }
            check(copyFailed, "invalid_item_encoding_prevents_incomplete_npc_copy");
            inventory.setContents(new ItemStack[] {validItem});
            UUID itemOwnerId = itemOwner.getUniqueId();
            ok(a, "npc remove " + itemOwner.getId());
            check(!exists(itemOwner), "corrected_item_allows_removal_retry");
            ok(a, "npc undo");
            NPC itemRestored = registry.getByUniqueId(itemOwnerId);
            check(itemRestored != null && ItemStack.matches(validItem,
                    itemRestored.getOrAddTrait(Inventory.class).getContents()[0]), "undo_retains_exact_native_item_components");
            var restoredInventory = itemRestored.getOrAddTrait(Inventory.class);
            restoredInventory.setContents(new ItemStack[0]);
            restoredInventory.save(inventoryKey);
            check(!inventoryKey.keyExists("0"), "explicit_inventory_clear_removes_the_saved_slot");

            var itemRewards = recoveredItemRewards;
            server.getCommands().getDispatcher().register(Commands.literal("itemrecoveryreward").executes(ctx -> itemRewards.incrementAndGet()));
            for (String itemPath : List.of("itemRequirements.0", "commands.0.itemCost.0")) {
                NPC costNpc = npc(registry, alice.getUUID(), "ItemCostRecovery", null);
                var costs = costNpc.getOrAddTrait(CommandTrait.class);
                costs.addCommand(new CommandTrait.NPCCommandBuilder("itemrecoveryreward", CommandTrait.Hand.RIGHT).experienceCost(2));
                costs.setExperienceCost(3);
                DataKey definition = new MemoryDataKey(); PersistenceLoader.save(costs, definition);
                definition.setString(itemPath + ".nbt", "{id:'missing:cost',count:1}");
                PersistenceLoader.load(costs, definition);
                int beforeReward = itemRewards.get(); alice.setExperienceLevels(20);
                costs.dispatch(alice, CommandTrait.Hand.RIGHT);
                check(itemRewards.get() == beforeReward && alice.experienceLevel == 20,
                        "unavailable_item_precedes_all_command_payments_" + itemPath);
                DataKey savedCosts = new MemoryDataKey(); PersistenceLoader.save(costs, savedCosts);
                check(definition.getRaw(itemPath).equals(savedCosts.getRaw(itemPath)), "unavailable_command_cost_is_persisted_" + itemPath);
                var editor = itemPath.startsWith("itemRequirements") ? new CommandTrait.ItemRequirementGUI(costs)
                        : new CommandTrait.ItemRequirementGUI(costs, 0);
                var editorContext = new MenuContext(null, new InventoryMenuSlot[45], new SimpleContainer(45), "audit");
                editor.initialise(editorContext); editor.onClose(alice);
                var afterEditor = new MemoryDataKey(); PersistenceLoader.save(costs, afterEditor);
                check(savedCosts.getRaw(itemPath).equals(afterEditor.getRaw(itemPath)), "opening_cost_editor_keeps_unavailable_definition_" + itemPath);
                ItemStorage.saveItem(savedCosts.getRelative(itemPath), new ItemStack(Items.DIAMOND));
                PersistenceLoader.load(costs, savedCosts); alice.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                costs.dispatch(alice, CommandTrait.Hand.RIGHT);
                check(alice.experienceLevel == 15
                        && alice.getInventory().getItem(0).getCount() == 2, "repaired_cost_executes_with_exact_payments_" + itemPath
                        + "_reward=" + (itemRewards.get() - beforeReward) + "_levels=" + alice.experienceLevel
                        + "_items=" + alice.getInventory().getItem(0).getCount());
                costNpc.destroy();
            }
            DataKey missingAction = new MemoryDataKey(); missingAction.setString("items.0.nbt", "{id:'missing:reward',count:1}");
            ItemAction itemAction = PersistenceLoader.load(ItemAction.class, missingAction);
            var editCallbacks = new AtomicInteger();
            var actionEditor = new ItemAction.ItemActionEditor(itemAction, result -> editCallbacks.incrementAndGet());
            actionEditor.initialise(new MenuContext(null, new InventoryMenuSlot[36], new SimpleContainer(36), "audit"));
            actionEditor.onClose(alice);
            var actionSaved = new MemoryDataKey(); PersistenceLoader.save(itemAction, actionSaved);
            check(editCallbacks.get() == 0 && missingAction.getRaw("items").equals(actionSaved.getRaw("items")),
                    "opening_action_editor_keeps_unavailable_definition");
            DataKey missingStock = new MemoryDataKey(); missingStock.setRaw("inventory", missingAction.getRaw("items"));
            NPCShopStorage shopStock = PersistenceLoader.load(NPCShopStorage.class, missingStock);
            var stockEditor = new InventoryViewer(shopStock);
            stockEditor.initialise(new MenuContext(null, new InventoryMenuSlot[36], new SimpleContainer(36), "audit"));
            stockEditor.onClose(alice);
            var stockSaved = new MemoryDataKey(); PersistenceLoader.save(shopStock, stockSaved);
            check(missingStock.getRaw("inventory").equals(stockSaved.getRaw("inventory")), "opening_stock_editor_keeps_unavailable_definition");

            var callbacks = new AtomicInteger();
            try {
                NPCCommandSelector.startWithCallback(npc -> { callbacks.incrementAndGet(); throw new IllegalArgumentException("fixture"); },
                        registry, a, new CommandContext(a, new String[] { "npc", "remove", control.getUniqueId().toString() }),
                        control.getUniqueId().toString());
            } catch (IllegalArgumentException expected) { }
            check(callbacks.get() == 1, "uuid_callback_failure_is_not_retried_as_a_name");
            var outcome = new boolean[2];
            server.getCommands().performPrefixedCommand(a.withCallback((success, result) -> { outcome[0] = true; outcome[1] = success; }),
                    "npc remove MissingOutcome");
            check(outcome[0] && !outcome[1], "native_command_failure_reaches_brigadier_callback");
            var rewards = new AtomicInteger();
            server.getCommands().getDispatcher().register(Commands.literal("removalauditreward").executes(ctx -> rewards.incrementAndGet()));
            check(!new Actions(new ItemLibrary(), new Economy()).runAll(List.of("player_command_as_op: npc remove MissingOutcome",
                    "player_command_as_op: removalauditreward"), alice, Component.empty()) && rewards.get() == 0,
                    "failed_citizens_command_stops_later_dialogue_actions");

            reject(a, "npc remove all");
            check(registry.iterator().hasNext(), "remove_all_requires_its_own_permission");
            var all = grant(bob, "citizens.admin.remove.all");
            ok(b, "npc remove all");
            check(!registry.iterator().hasNext() && named.iterator().hasNext(), "upstream_all_permission_clears_only_default_registry");
            ok(b, "npc undo all"); all.remove();
            check(registry.getByUniqueId(temporaryId) != null && !registry.getByUniqueId(temporaryId).data().get(NPC.Metadata.SHOULD_SAVE, true),
                    "bulk_undo_restores_temporary_npcs_too");
            var legacyAll = grant(bob, "citizens.npc.remove.all");
            ok(b, "npc remove all"); check(!registry.iterator().hasNext(), "existing_port_all_permission_remains_accepted");
            ok(b, "npc undo all"); legacyAll.remove();

            NPC live = npc(registry, alice.getUUID(), "SpawnRestore", level);
            live.setSneaking(true); live.getNavigator().getDefaultParameters().speedModifier(1.75F);
            live.getOrAddTrait(Spawned.class).setSpawned(true);
            if (!live.spawn(new Location(level, 2, -60, 2))) throw new AssertionError("Spawn restore setup failed");
            spawnedRestore = live.getUniqueId();
            ok(a, "npc remove " + live.getId()); ok(a, "npc undo");
        }

        NPC npc(NPCRegistry in, UUID owner, String name, ServerLevel at) {
            NPC npc = in.createNPC(EntityType.PIG, name); configure(npc, owner, at); return npc;
        }
        void configure(NPC npc, UUID owner, ServerLevel at) {
            npc.getOrAddTrait(Owner.class).setOwner(owner);
            npc.getOrAddTrait(Spawned.class).setSpawned(false);
            if (at != null) npc.getOrAddTrait(CurrentLocation.class).setLocation(new Location(at, 1, -60, 1));
        }
        void writeUid(ServerLevel at, UUID value) throws Exception {
            var path = DimensionType.getStorageFolder(at.dimension(), server.getWorldPath(LevelResource.ROOT)).resolve("uid.dat");
            Files.createDirectories(path.getParent());
            try (var out = new DataOutputStream(Files.newOutputStream(path))) {
                out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
            }
        }
        void add(Action action) { steps.add(() -> { action.run(); return true; }); }
        PermissionUtil.Attachment grant(ServerPlayer player, String permission) {
            var grant = PermissionUtil.grantTemporary(player, List.of(permission)); grants.add(grant); return grant;
        }
        void ok(CommandSourceStack source, String command) throws CommandSyntaxException {
            if (server.getCommands().getDispatcher().execute(command, source) != 1) throw new AssertionError(command);
        }
        void reject(CommandSourceStack source, String command) throws Exception {
            try { server.getCommands().getDispatcher().execute(command, source); }
            catch (CommandSyntaxException expected) { return; }
            throw new AssertionError("Command unexpectedly succeeded: " + command);
        }
        void chat(String message) {
            var event = new ServerChatEvent(alice, message, Component.literal(message));
            NeoForge.EVENT_BUS.post(event);
            if (!event.isCanceled()) throw new AssertionError("Prompt answer was not consumed");
        }
        AuditPlayer player(String name) {
            var player = new AuditPlayer(server, level, name); players.add(player); player.setPos(1, -60, 1);
            var connection = new Connection(PacketFlow.SERVERBOUND);
            player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    Connection.configureInMemoryPipeline(channel.pipeline(), PacketFlow.SERVERBOUND);
                    connection.configurePacketHandler(channel.pipeline());
                }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
        }
        void close() {
            NeoForge.EVENT_BUS.unregister(recorder);
            for (AuditPlayer player : players) ChatPrompts.abandon(player);
            grants.forEach(PermissionUtil.Attachment::remove);
            if (isolated) registry.deregisterAll();
            if (named != null) { named.deregisterAll(); CitizensAPI.removeNamedNPCRegistry(named.getName()); }
            for (AuditPlayer player : players) {
                if (server.getPlayerList().getPlayer(player.getUUID()) == player) server.getPlayerList().remove(player);
                else level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
                if (player.channel != null) player.channel.finishAndReleaseAll();
            }
        }
    }

    private static boolean exists(NPC npc) { return npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) != null; }
    private static void check(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("citizens").info("[REMOVALAUDIT] PASS {}", name);
    }
    public static class BrokenSnapshot extends Trait {
        public BrokenSnapshot() { super("removalauditfailure"); }
        @Override public void save(DataKey key) { throw new IllegalStateException("Injected snapshot failure"); }
    }
    public static class RemovalRecorder {
        NPC lastNpc; CommandSourceStack lastSource;
        @SubscribeEvent public void removed(NPCRemoveByCommandSenderEvent event) { lastNpc = event.getNPC(); lastSource = event.getSource(); }
    }
    private static class AuditPlayer extends ServerPlayer {
        final List<String> messages = new ArrayList<>(); EmbeddedChannel channel;
        AuditPlayer(MinecraftServer server, ServerLevel level, String name) { super(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
    }
}
