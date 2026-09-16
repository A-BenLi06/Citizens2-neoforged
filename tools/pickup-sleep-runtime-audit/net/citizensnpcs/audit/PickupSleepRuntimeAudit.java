package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.api.trait.trait.Inventory;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.npc.entity.EntityHumanNPC;
import net.citizensnpcs.trait.SkinTrait;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class PickupSleepRuntimeAudit {
    private static State state;
    private static boolean forced, finished;
    private static int passed;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void pickup(ItemEntityPickupEvent.Pre event) {
        if (state == null) return;
        if (state.denied.contains(event.getItemEntity().getUUID())) event.setCanPickup(TriState.FALSE);
        if (state.forcedItems.contains(event.getItemEntity().getUUID())) event.setCanPickup(TriState.TRUE);
    }
    @SubscribeEvent public static void pickupXp(PlayerXpEvent.PickupXp event) {
        if (state != null && state.deniedXp.contains(event.getOrb().getUUID())) event.setCanceled(true);
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) {
            for (int x = 0; x <= 3; x++) for (int z = 0; z <= 3; z++) level.setChunkForced(x, z, true);
            forced = true;
        }
        try {
            for (int x = 0; x <= 3; x++) for (int z = 0; z <= 3; z++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(x, z)) || !level.isPositionEntityTicking(new BlockPos(x * 16, -60, z * 16))) {
                    if (server.getTickCount() > 1500) throw new AssertionError("Fixture loading timed out");
                    return;
                }
            }
            if (state == null) state = new State(server);
            if (state.next()) return;
            LoggerFactory.getLogger("citizens").info("[PICKUPSLEEPAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[PICKUPSLEEPAUDIT] FAILED " + (state == null ? "setup" : state.phase), failure);
        }
        finished = true;
        try { if (state != null) state.close(); }
        catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[PICKUPSLEEPAUDIT] FAILED cleanup", failure); }
        server.halt(false);
    }

    @FunctionalInterface private interface Test { void run() throws Exception; }
    private record Step(String name, Test test) { }
    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final List<Step> steps = new ArrayList<>();
        final List<NPC> npcs = new ArrayList<>();
        final List<Entity> entities = new ArrayList<>();
        final Set<UUID> denied = new HashSet<>(), forcedItems = new HashSet<>(), deniedXp = new HashSet<>();
        ServerPlayer viewer;
        EmbeddedChannel channel;
        PermissionUtil.Attachment permission;
        NPC collector, zombie, itemNPC;
        Inventory inventory;
        ItemEntity drop, delayed, owned, rejected, partial, zombieSword;
        ExperienceOrb firstXp, secondXp, cancelledXp;
        Arrow arrow, disallowedArrow;
        int cursor;
        String phase;
        boolean isolated;

        State(MinecraftServer server) {
            this.server = server; level = server.overworld();
            if (!Files.exists(Path.of("pickup-sleep-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("Pickup/sleep audit requires an empty isolated fixture");
            isolated = true; level.setDayTime(18000);
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            viewer = player();
            permission = PermissionUtil.grantTemporary(viewer, List.of("citizens.npc.select", "citizens.npc.pickupitems", "citizens.npc.inventory"));
            collector = npc(EntityType.PLAYER, "Collector");
            collector.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, new ItemStack(Items.IRON_SWORD));
            collector.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HELMET, new ItemStack(Items.DIAMOND_HELMET));
            spawn(collector, 4.5, 4.5); inventory = collector.getOrAddTrait(Inventory.class);
            zombie = npc(EntityType.ZOMBIE, "MobCollector"); spawn(zombie, 30.5, 4.5);
            script();
        }

        boolean next() throws Exception {
            if (cursor == steps.size()) return false;
            Step step = steps.get(cursor++); phase = step.name; step.test.run(); return true;
        }
        void step(String name, Test test) { steps.add(new Step(name, test)); }
        void waitTicks(int ticks) { for (int i = 0; i < ticks; i++) step("wait_" + steps.size(), () -> { }); }
        EntityHumanNPC human() { return (EntityHumanNPC) collector.getEntity(); }

        void script() {
            step("inventory_authority", () -> {
                check(human().getMainHandItem().is(Items.IRON_SWORD), "automatic_inventory_preserves_stored_equipment_hand");
                check(human().getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "automatic_inventory_preserves_armor");
                ItemStack[] batch = new ItemStack[36]; batch[0] = new ItemStack(Items.STONE, 3); batch[1] = new ItemStack(Items.EMERALD, 4); batch[3] = new ItemStack(Items.DIAMOND, 5);
                inventory.setContents(batch); batch[1].setCount(40);
                check(human().getInventory().getItem(0).getCount() == 3 && human().getInventory().getItem(1).getCount() == 4
                        && human().getInventory().getItem(3).getCount() == 5, "bulk_inventory_update_keeps_all_slots_and_copies_input");
                human().getInventory().setItem(12, new ItemStack(Items.GOLD_INGOT, 9));
                check(inventory.getInventoryView().getItem(12).getCount() == 9 && inventory.getContents()[12].getCount() == 9,
                        "native_inventory_changes_are_visible_to_menu_and_snapshot");
                inventory.getInventoryView().setItem(5, new ItemStack(Items.APPLE, 8));
                check(human().getInventory().getItem(5).getCount() == 8 && human().getInventory().getItem(12).getCount() == 9,
                        "menu_edit_does_not_overwrite_unrelated_native_change");
                inventory.setContents(new ItemStack[36]);
                drop = drop(collector, new ItemStack(Items.EMERALD, 2));
            });
            waitTicks(3);
            step("default_and_enable", () -> {
                check(!drop.isRemoved() && human().getInventory().countItem(Items.EMERALD) == 0, "default_npc_does_not_pick_up_items");
                select(collector); bad("npc pickupitems --set invalid"); bad("npc pickupitems --bogus true");
                check(!collector.data().has(NPC.Metadata.PICKUP_ITEMS), "invalid_pickup_input_does_not_write_metadata");
                collector.setProtected(false); ok("npc pickupitems"); collector.setProtected(true);
                check(collector.data().<Boolean>get(NPC.Metadata.PICKUP_ITEMS), "pickup_toggle_uses_effective_disabled_default");
                ok("npc inventory"); check(viewer.containerMenu instanceof ChestMenu, "native_inventory_menu_opens");
            });
            waitTicks(3);
            step("picked_inventory", () -> {
                check(drop.isRemoved() && human().getInventory().countItem(Items.EMERALD) == 2, "enabled_npc_collects_native_item");
                check(inventory.getInventoryView().countItem(Items.EMERALD) == 2, "open_menu_sees_live_pickup");
                inventory.getInventoryView().setItem(5, new ItemStack(Items.APPLE, 3));
                check(human().getInventory().countItem(Items.EMERALD) == 2 && human().getInventory().countItem(Items.APPLE) == 3,
                        "editing_during_pickup_preserves_collected_items");
                var copy = collector.copy(); check(copy.getOrAddTrait(Inventory.class).getContents()[0].is(Items.EMERALD), "picked_item_survives_npc_copy"); copy.destroy();
                collector.despawn(); check(!(viewer.containerMenu instanceof ChestMenu), "despawn_closes_bound_inventory_menu");
                spawn(collector, 4.5, 4.5); inventory = collector.getOrAddTrait(Inventory.class);
                check(human().getInventory().countItem(Items.EMERALD) == 2 && human().getInventory().countItem(Items.APPLE) == 3,
                        "picked_and_edited_items_survive_respawn");
                delayed = drop(collector, new ItemStack(Items.GOLD_INGOT, 2)); delayed.setPickUpDelay(200);
                owned = drop(collector, new ItemStack(Items.DIAMOND, 2)); owned.setTarget(UUID.randomUUID());
                rejected = drop(collector, new ItemStack(Items.REDSTONE, 2)); denied.add(rejected.getUUID());
            });
            waitTicks(3);
            step("pickup_policies", () -> {
                check(!delayed.isRemoved() && !owned.isRemoved() && !rejected.isRemoved(), "pickup_respects_delay_owner_and_neoforge_denial");
                check(human().getInventory().countItem(Items.GOLD_INGOT) == 0 && human().getInventory().countItem(Items.DIAMOND) == 0
                        && human().getInventory().countItem(Items.REDSTONE) == 0, "denied_pickups_do_not_change_inventory");
                delayed.discard(); owned.discard(); rejected.discard(); denied.clear();
                ItemStack[] full = new ItemStack[36]; for (int i = 0; i < full.length; i++) full[i] = new ItemStack(Items.DIRT, 64);
                inventory.setContents(full); partial = drop(collector, new ItemStack(Items.DIAMOND, 5));
            });
            waitTicks(3);
            step("capacity", () -> {
                check(!partial.isRemoved() && partial.getItem().getCount() == 5, "full_inventory_leaves_item_entity_unchanged");
                inventory.setItem(10, new ItemStack(Items.DIAMOND, 63));
            });
            waitTicks(3);
            step("partial_pickup", () -> {
                check(!partial.isRemoved() && partial.getItem().getCount() == 4 && human().getInventory().getItem(10).getCount() == 64,
                        "partial_pickup_preserves_exact_remaining_count");
                partial.discard(); inventory.setContents(new ItemStack[36]);
                ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE); tool.setDamageValue(60);
                tool.enchant(level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.MENDING), 1);
                inventory.setItem(0, tool);
                firstXp = xp(collector, 3); secondXp = xp(collector, 5);
            });
            step("xp_cooldown", () -> {
                check(firstXp.isRemoved() != secondXp.isRemoved() && human().takeXpDelay > 0, "xp_pickup_uses_native_cooldown");
            });
            waitTicks(4);
            step("mending", () -> {
                check(firstXp.isRemoved() && secondXp.isRemoved() && human().getMainHandItem().getDamageValue() == 44,
                        "xp_pickup_applies_native_mending");
                var data = new MemoryDataKey(); collector.saveSnapshot(data);
                var copy = collector.copy();
                check(copy.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND).getDamageValue() == 44,
                        "immediate_equipment_snapshot_keeps_mended_durability"); copy.destroy();
                collector.despawn(); spawn(collector, 4.5, 4.5); inventory = collector.getOrAddTrait(Inventory.class);
                check(human().getMainHandItem().getDamageValue() == 44, "mended_item_survives_respawn");
                inventory.setContents(new ItemStack[36]); firstXp = xp(collector, 7); cancelledXp = xp(collector, 11); deniedXp.add(cancelledXp.getUUID());
            });
            waitTicks(4);
            step("xp_and_arrows", () -> {
                check(firstXp.isRemoved() && human().totalExperience == 7 && !cancelledXp.isRemoved(), "xp_points_and_pickup_cancellation_are_respected");
                cancelledXp.discard(); deniedXp.clear();
                arrow = arrow(collector, AbstractArrow.Pickup.ALLOWED); disallowedArrow = arrow(collector, AbstractArrow.Pickup.DISALLOWED);
            });
            waitTicks(3);
            step("arrow_and_disable", () -> {
                check(arrow.isRemoved() && !disallowedArrow.isRemoved() && human().getInventory().countItem(Items.ARROW) == 1,
                        "arrow_pickup_keeps_native_allowed_disallowed_rules");
                disallowedArrow.discard(); select(collector); ok("npc pickupitems --set false"); drop = drop(collector, new ItemStack(Items.COAL, 2));
                drop.playerTouch(human()); check(!drop.isRemoved(), "direct_item_touch_respects_disabled_npc_policy");
                itemNpcProtection();
                select(zombie); ok("npc pickupitems --set true");
                check(((Mob) zombie.getEntity()).canPickUpLoot(), "mob_pickup_flag_applies_immediately");
                zombieSword = drop(zombie, new ItemStack(Items.DIAMOND_SWORD));
            });
            waitTicks(5);
            step("mob_and_disabled", () -> {
                check(!drop.isRemoved() && human().getInventory().countItem(Items.COAL) == 0, "disabled_pickup_stays_disabled_across_ticks");
                check(zombieSword.isRemoved() && ((Mob) zombie.getEntity()).getMainHandItem().is(Items.DIAMOND_SWORD), "enabled_mob_uses_native_equipment_pickup");
                var copy = zombie.copy(); check(copy.getOrAddTrait(Equipment.class).get(Equipment.EquipmentSlot.HAND).is(Items.DIAMOND_SWORD), "mob_pickup_equipment_survives_immediate_copy"); copy.destroy();
                select(zombie); ok("npc pickupitems --set false"); check(!((Mob) zombie.getEntity()).canPickUpLoot(), "mob_pickup_can_be_disabled_again");
                sleepRules();
                horseMenus();
                storageAndMenus();
            });
        }

        void itemNpcProtection() {
            itemNPC = npc(EntityType.ITEM, "ItemNPC"); itemNPC.data().setPersistent(NPC.Metadata.ITEM_ID, "minecraft:diamond");
            spawn(itemNPC, 20.5, 20.5);
            ItemEntity item = (ItemEntity) itemNPC.getEntity(); item.setNoPickUpDelay(); forcedItems.add(item.getUUID());
            int before = viewer.getInventory().countItem(Items.DIAMOND); item.playerTouch(viewer);
            check(!item.isRemoved() && viewer.getInventory().countItem(Items.DIAMOND) == before, "item_npc_cannot_be_collected_even_by_forced_pickup_event");
            item.setNeverPickUp();
            check(item.saveWithoutId(new net.minecraft.nbt.CompoundTag()).getShort("Age") == -32768, "item_npc_has_unlimited_lifetime");
            forcedItems.clear();
        }

        void sleepRules() {
            SleepPlayer awake = sleeper("SleepAwake"), asleep = sleeper("SleepAsleep"), third = sleeper("SleepThird");
            List<ServerPlayer> humans = List.of(awake, asleep, third);
            SleepStatus vanilla = new SleepStatus(); vanilla.update(humans);
            check(vanilla.sleepersNeeded(50) == 2 && !vanilla.areEnoughSleeping(50), "sleep_without_npcs_keeps_native_counts");
            List<ServerPlayer> mixed = new ArrayList<>(humans);
            for (int i = 0; i < 5; i++) mixed.add(new EntityHumanNPC(server, level, new GameProfile(UUID.randomUUID(), "Ignored" + i), collector));
            SleepStatus status = new SleepStatus();
            check(!status.update(mixed) && status.amountSleeping() == 5 && status.sleepersNeeded(50) == 4,
                    "ignored_npcs_count_as_faux_sleepers_without_notifications");
            check(status.areEnoughSleeping(50) && !status.areEnoughDeepSleeping(50, mixed), "ignored_npcs_alone_cannot_skip_night");
            asleep.sleeping = true; status.update(mixed);
            check(!status.areEnoughDeepSleeping(50, mixed), "actual_sleeper_must_reach_deep_sleep");
            asleep.deep = true;
            check(status.areEnoughDeepSleeping(50, mixed), "faux_sleepers_follow_original_percentage_semantics");
            check(!status.areEnoughDeepSleeping(100, mixed), "awake_real_players_still_block_full_percentage_sleep");
            awake.sleeping = awake.deep = true; third.sleeping = third.deep = true; status.update(mixed);
            check(status.areEnoughSleeping(100) && status.areEnoughDeepSleeping(100, mixed), "all_real_players_sleep_without_npc_blocking");
            third.spectator = true; third.sleeping = third.deep = false; status.update(mixed);
            check(status.sleepersNeeded(100) == 7 && status.areEnoughDeepSleeping(100, mixed), "spectators_remain_excluded_from_active_sleep_count");
        }

        void horseMenus() throws Exception {
            NPC horse = npc(EntityType.HORSE, "HorseInventory"); spawn(horse, 40.5, 20.5);
            AbstractHorse entity = (AbstractHorse) horse.getEntity();
            entity.getInventory().setItem(0, new ItemStack(Items.SADDLE));
            Inventory stored = horse.getOrAddTrait(Inventory.class);
            check(stored.getContents()[0].is(Items.SADDLE), "late_inventory_attach_preserves_native_horse_saddle");
            stored.openInventory(viewer); ChestMenu menu = (ChestMenu) viewer.containerMenu;
            int padded = entity.getInventory().getContainerSize();
            check(padded < menu.getContainer().getContainerSize() && !menu.getSlot(padded).mayPlace(new ItemStack(Items.DIAMOND)),
                    "horse_menu_padding_rejects_placement");
            menu.setCarried(new ItemStack(Items.DIAMOND, 3));
            menu.clicked(padded, 0, ClickType.PICKUP, viewer);
            check(menu.getCarried().getCount() == 3 && menu.getSlot(padded).getItem().isEmpty(), "click_on_padding_preserves_cursor_items");
            menu.setCarried(ItemStack.EMPTY);
            menu.clicked(0, 0, ClickType.PICKUP, viewer);
            check(menu.getCarried().is(Items.SADDLE) && entity.getInventory().getItem(0).isEmpty(), "horse_menu_click_reads_native_slot");
            menu.clicked(0, 0, ClickType.PICKUP, viewer);
            check(menu.getCarried().isEmpty() && entity.getInventory().getItem(0).is(Items.SADDLE), "horse_menu_click_writes_native_slot");
            for (int i = 0; i < padded; i++) entity.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
            viewer.getInventory().setItem(9, new ItemStack(Items.EMERALD, 7));
            menu.clicked(menu.getContainer().getContainerSize(), 0, ClickType.QUICK_MOVE, viewer);
            check(viewer.getInventory().getItem(9).getCount() == 7 && stored.getInventoryView().countItem(Items.EMERALD) == 0,
                    "quick_move_does_not_consume_items_into_padding");
            var collectDrops = net.citizensnpcs.commands.NPCCommands.class.getDeclaredMethod("addToContainer",
                    net.minecraft.world.Container.class, ItemStack.class);
            collectDrops.setAccessible(true);
            ItemStack drops = new ItemStack(Items.EMERALD, 5);
            ItemStack remainder = (ItemStack) collectDrops.invoke(null, stored.getInventoryView(), drops);
            check(remainder.getCount() == 5 && drops.getCount() == 5 && stored.getInventoryView().countItem(Items.EMERALD) == 0,
                    "block_drop_collection_keeps_remainder_when_native_slots_are_full");
            entity.getInventory().setItem(0, ItemStack.EMPTY);
            menu.clicked(menu.getContainer().getContainerSize(), 0, ClickType.QUICK_MOVE, viewer);
            check(viewer.getInventory().getItem(9).isEmpty() && entity.getInventory().getItem(0).getCount() == 7,
                    "quick_move_uses_available_native_slot");
            stored.setItem(0, new ItemStack(Items.EMERALD, 63));
            remainder = (ItemStack) collectDrops.invoke(null, stored.getInventoryView(), drops);
            check(remainder.getCount() == 4 && entity.getInventory().getItem(0).getCount() == 64,
                    "block_drop_collection_keeps_exact_partial_remainder");
            stored.setItem(0, ItemStack.EMPTY);
            remainder = (ItemStack) collectDrops.invoke(null, stored.getInventoryView(), drops);
            check(remainder.isEmpty() && entity.getInventory().getItem(0).getCount() == 5 && drops.getCount() == 5,
                    "block_drop_collection_fills_native_slot_without_mutating_input");
            stored.setItem(0, new ItemStack(Items.SADDLE));
            check(entity.getMainHandItem().isEmpty(), "horse_saddle_slot_is_not_mirrored_to_main_hand");
            horse.despawn(); spawn(horse, 40.5, 20.5);
            check(((AbstractHorse) horse.getEntity()).getInventory().getItem(0).is(Items.SADDLE), "horse_native_inventory_survives_respawn");
            horse.destroy();
        }

        void storageAndMenus() throws Exception {
            NPC storage = npc(EntityType.PIG, "StorageOnly"); Inventory stored = storage.getOrAddTrait(Inventory.class);
            stored.setContents(new ItemStack[] {new ItemStack(Items.APPLE, 3), new ItemStack(Items.BREAD, 4), new ItemStack(Items.CARROT, 5)});
            stored.openInventory(viewer); stored.getInventoryView().setItem(1, new ItemStack(Items.POTATO, 6));
            check(stored.getContents()[0].getCount() == 3 && stored.getContents()[1].is(Items.POTATO) && stored.getContents()[2].getCount() == 5,
                    "unspawned_inventory_edits_preserve_other_stored_slots");
            storage.destroy(); check(!(viewer.containerMenu instanceof ChestMenu), "removing_inventory_owner_closes_menu");
            select(collector); ok("npc inventory");
            UUID id = collector.getUniqueId(); int arrows = human().getInventory().countItem(Items.ARROW);
            server.getCommands().getDispatcher().execute("citizens save", server.createCommandSourceStack().withPermission(4));
            server.getCommands().getDispatcher().execute("citizens reload", server.createCommandSourceStack().withPermission(4));
            if (net.citizensnpcs.Settings.Setting.WARN_ON_RELOAD.asBoolean()) server.getCommands().getDispatcher().execute("citizens reload", server.createCommandSourceStack().withPermission(4));
            collector = CitizensAPI.getNPCRegistry().getByUniqueId(id); inventory = collector.getOrAddTrait(Inventory.class);
            check(!(viewer.containerMenu instanceof ChestMenu), "reload_closes_inventory_bound_to_old_entity");
            check(inventory.getContents()[0].is(Items.ARROW) && inventory.getContents()[0].getCount() == arrows, "picked_inventory_survives_actual_file_reload");
        }

        SleepPlayer sleeper(String name) { return new SleepPlayer(server, level, name); }
        NPC npc(EntityType<?> type, String name) {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(type, name); npcs.add(npc);
            npc.getOrAddTrait(Owner.class).setOwner(viewer.getUUID()); npc.getOrAddTrait(Spawned.class).setSpawned(false);
            if (type == EntityType.PLAYER) npc.getOrAddTrait(SkinTrait.class).setFetchDefaultSkin(false);
            return npc;
        }
        void spawn(NPC npc, double x, double z) { check(npc.spawn(new Location(level, x, -60, z)), "spawn_" + npc.getName()); }
        ItemEntity drop(NPC npc, ItemStack stack) {
            Entity at = npc.getEntity(); ItemEntity item = new ItemEntity(level, at.getX(), at.getY(), at.getZ(), stack);
            item.setDeltaMovement(0, 0, 0); item.setNoPickUpDelay(); level.addFreshEntity(item); entities.add(item); return item;
        }
        ExperienceOrb xp(NPC npc, int value) {
            Entity at = npc.getEntity(); var orb = new ExperienceOrb(level, at.getX(), at.getY(), at.getZ(), value);
            orb.setDeltaMovement(0, 0, 0); level.addFreshEntity(orb); entities.add(orb); return orb;
        }
        Arrow arrow(NPC npc, AbstractArrow.Pickup policy) {
            Entity at = npc.getEntity(); var arrow = new Arrow(EntityType.ARROW, level); arrow.setPos(at.position()); arrow.setNoPhysics(true); arrow.pickup = policy;
            level.addFreshEntity(arrow); entities.add(arrow); return arrow;
        }
        void select(NPC npc) { CitizensAPI.getDefaultNPCSelector().select(source(), npc); }
        CommandSourceStack source() { return viewer.createCommandSourceStack().withPermission(0); }
        void ok(String command) throws Exception {
            try { if (server.getCommands().getDispatcher().execute(command, source()) <= 0) throw new AssertionError(command); }
            catch (CommandSyntaxException failure) { throw new AssertionError(command, failure); }
        }
        void bad(String command) throws Exception {
            try { server.getCommands().getDispatcher().execute(command, source()); }
            catch (CommandSyntaxException expected) { return; }
            throw new AssertionError("Unexpected success: " + command);
        }
        ServerPlayer player() {
            var profile = new GameProfile(UUID.randomUUID(), "PickupViewer");
            var player = new ServerPlayer(server, level, profile, ClientInformation.createDefault()); player.setPos(45, -60, 45);
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) { connection.configurePacketHandler(channel.pipeline()); }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
        }
        void close() {
            if (permission != null) permission.remove();
            if (isolated) CitizensAPI.getNPCRegistry().deregisterAll();
            entities.forEach(Entity::discard);
            if (viewer != null) server.getPlayerList().remove(viewer);
            if (channel != null) channel.finishAndReleaseAll();
        }
    }
    private static final class SleepPlayer extends ServerPlayer {
        boolean sleeping, deep, spectator;
        SleepPlayer(MinecraftServer server, ServerLevel level, String name) { super(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault()); }
        @Override public boolean isSleeping() { return sleeping; }
        @Override public boolean isSleepingLongEnough() { return deep; }
        @Override public boolean isSpectator() { return spectator; }
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("citizens").info("[PICKUPSLEEPAUDIT] PASS {}", name);
    }
}
