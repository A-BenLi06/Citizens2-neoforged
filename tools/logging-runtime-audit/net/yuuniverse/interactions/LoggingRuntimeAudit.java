package net.yuuniverse.interactions;

import java.util.*;
import com.daqem.grieflogger.database.service.Services;
import com.daqem.grieflogger.model.action.*;
import com.daqem.grieflogger.model.history.ItemHistory;
import com.simibubi.create.AllBlocks;
import dev.northbladetech.neoprotect.NeoProtect;
import dev.northbladetech.neoprotect.model.ActionType;
import dev.northbladetech.neoprotect.rollback.RollbackSelector;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Real native use, menus, world rollback and independent log owners in a dedicated synthetic world. */
@EventBusSubscriber(modid="interactions")
public final class LoggingRuntimeAudit {
    private static int passed, stage, readyTick;
    private static ServerPlayer actor;
    private static io.netty.channel.embedded.EmbeddedChannel channel;
    private static final BlockPos vanillaPos = new BlockPos(3,-60,3), moddedPos = new BlockPos(5,-60,3), chestPos = new BlockPos(7,-60,3);
    private static UUID pigId;
    private static net.yuuniverse.economy.core.CommerceRequest tradeRequest;
    private static net.yuuniverse.economy.api.CommerceOutcome tradeOutcome;
    private static net.yuuniverse.economy.core.CommerceTerms tradeTerms;
    private static String shopId, currencyId;
    private static long balanceBeforeTrade;
    private static final BlockPos shopPos=new BlockPos(9,-60,3);
    private static final BlockPos doorPos=new BlockPos(11,-60,3);
    private static final BlockPos burstPos=new BlockPos(13,-60,7);
    private static final int BURST_PLACEMENTS=96;
    private static long burstNanos;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var phase = System.getenv("CITIZENS_LOGGING_AUDIT_PHASE");
        if ("shutdown".equals(phase) || "shutdown-recover".equals(phase)) {
            LoggingShutdownRuntimeAudit.tick(event);
            return;
        }
        if ("storage".equals(phase) || "storage-recover".equals(phase)) {
            LoggingStorageRuntimeAudit.tick(event);
            return;
        }
        var server=event.getServer(); var level=server.overworld();
        if(stage==99) return;
        level.setChunkForced(0,0,true);
        if(server.getTickCount()<20 || !level.isPositionEntityTicking(new BlockPos(1,-60,1))) return;
        try {
            if(stage==0) {
                stage=1;
                if ("recover".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"))) { recover(server); return; }
                actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"LoggingActor"),ClientInformation.createDefault());
                actor.setPos(4,-60,4); channel=connect(actor); actor.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                server.getCommands().getDispatcher().execute("neoprotect inspect",actor.createCommandSourceStack().withPermission(2));
                check(NeoProtect.service().isInspecting(actor),"native_neoprotect_operator_inspection_enabled");
                server.getCommands().getDispatcher().execute("neoprotect inspect",actor.createCommandSourceStack().withPermission(2));
                check(!NeoProtect.service().isInspecting(actor),"native_neoprotect_operator_inspection_disabled");
                try {server.getCommands().getDispatcher().execute("neoprotect inspect",actor.createCommandSourceStack().withPermission(0));throw new AssertionError("Unauthorized inspect");}
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){check(true,"native_neoprotect_inspection_rejects_non_operator");}
                try {server.getCommands().getDispatcher().execute("gl inspect",actor.createCommandSourceStack().withPermission(0));throw new AssertionError("Unauthorized GL inspect");}
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException expected){check(true,"native_grieflogger_inspection_rejects_non_operator");}
                place(vanillaPos,new ItemStack(Items.STONE)); place(moddedPos,AllBlocks.SHAFT.asStack());
                level.removeBlock(doorPos.above(),false);place(doorPos,new ItemStack(Items.OAK_DOOR));
                check(level.getBlockState(doorPos).is(Blocks.OAK_DOOR)&&level.getBlockState(doorPos.above()).is(Blocks.OAK_DOOR),"native_door_use_places_two_block_states");
                check(level.getBlockState(vanillaPos).is(Blocks.STONE)&&level.getBlockState(moddedPos).is(AllBlocks.SHAFT.get()),"native_item_use_places_vanilla_and_create_blocks");
                place(chestPos,new ItemStack(Items.CHEST));
                var chest=(Container)level.getBlockEntity(chestPos); var stack=new ItemStack(Items.PAPER,7); stack.set(DataComponents.CUSTOM_NAME,Component.literal("Logging components"));chest.setItem(0,stack);
                actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                var hit=new BlockHitResult(Vec3.atCenterOf(chestPos),Direction.UP,chestPos,false);
                check(actor.gameMode.useItemOn(actor,level,ItemStack.EMPTY,InteractionHand.MAIN_HAND,hit).consumesAction(),"native_chest_use_opens_container");
                actor.containerMenu.clicked(0,0,ClickType.PICKUP,actor); actor.closeContainer();
                check(chest.getItem(0).isEmpty()&&actor.getInventory().countItem(Items.PAPER)==7,"native_container_take_moves_exact_component_stack");
                actor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
                var pig=EntityType.PIG.create(level);pig.setPos(4,-60,6);level.addFreshEntity(pig);pigId=pig.getUUID();pig.hurt(level.damageSources().playerAttack(actor),1000);
                check(!pig.isAlive(),"actual_player_damage_kills_ordinary_entity");
                var npc=net.citizensnpcs.api.CitizensAPI.getNPCRegistry().createNPC(EntityType.PIG,"Logging protected");
                npc.spawn(new net.citizensnpcs.api.util.Location(level,4,-60,7));var npcEntity=(net.minecraft.world.entity.LivingEntity)npc.getEntity();
                npcEntity.hurt(level.damageSources().playerAttack(actor),1000);
                check(npcEntity.isAlive()&&npc.isSpawned(),"citizens_protection_preserves_owned_entity_during_native_attack");npc.destroy();
                // Keep the previously taken paper while fixture placement equips its own hotbar slot.
                actor.getInventory().selected=8;
                actor.gameMode.changeGameModeForPlayer(GameType.CREATIVE);
                var burstStart=System.nanoTime();
                for(int i=0;i<BURST_PLACEMENTS;i++) {
                    place(burstPos,new ItemStack(Items.STONE));
                    if(!actor.gameMode.destroyBlock(burstPos))throw new AssertionError("Native burst break rejected");
                }
                burstNanos=System.nanoTime()-burstStart;
                check(level.getBlockState(burstPos).isAir(),"native_placement_break_burst_finishes_with_actual_empty_world");
                readyTick=server.getTickCount()+45;return;
            }
            if(server.getTickCount()<readyTick)return;
            if(stage==2){verifyCommerce(server);return;}
            NeoProtect.service().flush();
            var vanilla=NeoProtect.service().lookup(level,vanillaPos,0,100);
            var modded=NeoProtect.service().lookup(level,moddedPos,0,100);
            var container=NeoProtect.service().lookup(level,chestPos,0,100);
            check(vanilla.stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE).count()==1,"neoprotect_logs_one_native_vanilla_placement_with_actual_actor");
            check(modded.stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE&&e.itemId().equals("create:shaft")).count()==1,"neoprotect_logs_one_native_modded_placement_with_registry_identity");
            var door=NeoProtect.service().lookup(level,doorPos,0,100);var upperDoor=NeoProtect.service().lookup(level,doorPos.above(),0,100);
            check(door.stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE).count()==1
                    &&upperDoor.stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE).count()==1,"native_multiplace_records_each_changed_block_once");
            var doorRollback=NeoProtect.service().rollback(level,doorPos,1,60,actor.getGameProfile().getName(),RollbackSelector.parse("blocks"));
            org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGAUDIT] door rollback={} lower={} upper={}",doorRollback,level.getBlockState(doorPos),level.getBlockState(doorPos.above()));
            // Removing either half triggers the native door neighbor update, so the already restored half is skipped.
            check(doorRollback.blockChanges()==1&&doorRollback.skippedEntries()==1&&level.getBlockState(doorPos).isAir()&&level.getBlockState(doorPos.above()).isAir(),"native_multiplace_rollback_restores_both_halves_with_native_neighbor_update");
            check(Services.BLOCK.getBlockHistory(level,vanillaPos).stream().filter(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&e.getAction()==BlockAction.PLACE_BLOCK).count()==1,"grieflogger_independently_logs_one_vanilla_placement");
            check(Services.BLOCK.getBlockHistory(level,moddedPos).stream().filter(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&e.getAction()==BlockAction.PLACE_BLOCK).count()==1,"grieflogger_independently_logs_one_modded_placement");
            var burst=NeoProtect.service().lookup(level,burstPos,0,1000);
            check(burst.stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE).count()==BURST_PLACEMENTS
                    &&burst.stream().filter(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_BREAK).count()==BURST_PLACEMENTS,
                    "neoprotect_burst_retains_every_actual_placement_and_break_once");
            var glBurst=Services.BLOCK.getBlockHistory(level,burstPos);
            check(glBurst.stream().filter(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&e.getAction()==BlockAction.PLACE_BLOCK).count()==BURST_PLACEMENTS
                    &&glBurst.stream().filter(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&e.getAction()==BlockAction.BREAK_BLOCK).count()==BURST_PLACEMENTS,
                    "grieflogger_burst_retains_every_actual_placement_and_break_once");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGAUDIT] burst={} native mutations in {} ms; independent histories={}/{}",
                    BURST_PLACEMENTS*2,burstNanos/1_000_000.0,burst.size(),glBurst.size());
            check(container.stream().anyMatch(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.CONTAINER_TAKE&&e.itemCount()==7),"neoprotect_native_menu_take_retains_item_quantity");
            var glContainer=Services.CONTAINER.getHistory(level,chestPos);
            check(glContainer.stream().anyMatch(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&e.getAction()==ItemAction.REMOVE_ITEM&&e instanceof ItemHistory h
                    &&h.getItemStack().getCount()==7&&h.getItemStack().toItemStack().getHoverName().getString().equals("Logging components")),"grieflogger_native_menu_take_preserves_full_components");
            var containerPreview=NeoProtect.service().previewRollback(level,chestPos,0,60,actor.getGameProfile().getName(),RollbackSelector.parse("containers"));
            check(containerPreview.containerTransactions()==1&&containerPreview.itemUnits()==7&&((Container)level.getBlockEntity(chestPos)).getItem(0).isEmpty(),
                    "native_container_rollback_preview_retains_exact_units_without_mutation");
            var containerRollback=NeoProtect.service().rollback(level,chestPos,0,60,actor.getGameProfile().getName(),RollbackSelector.parse("containers"));
            var restored=((Container)level.getBlockEntity(chestPos)).getItem(0);
            check(containerRollback.containerTransactions()==1&&restored.getCount()==7&&restored.getHoverName().getString().equals("Logging components"),
                    "native_container_rollback_restores_exact_components_and_quantity");
            check(actor.getInventory().countItem(Items.PAPER)==7,
                    "world_container_rollback_does_not_reverse_player_inventory");
            var filters=new com.daqem.grieflogger.command.filter.FilterList(List.of(new com.daqem.grieflogger.command.filter.RadiusFilter(20)),actor.createCommandSourceStack());
            check(Services.BLOCK.getFilteredBlockHistory(level,filters).stream().anyMatch(e->e.getAction()==BlockAction.KILL_ENTITY&&e.getUser().getUuid().orElseThrow().equals(actor.getUUID())),"grieflogger_native_entity_death_retains_actual_killer");
            var economy=net.yuuniverse.economy.api.YuuniverseEconomyApi.current().orElseThrow();var currency=economy.defaultCurrency().id();
            economy.ensurePlayerAccount(actor.getUUID(),actor.getGameProfile().getName());economy.credit(actor.getUUID(),currency,1000,"logging-seed:"+actor.getUUID(),"logging fixture");
            var balance=economy.balance(actor.getUUID(),currency);
            var preview=NeoProtect.service().previewRollback(level,vanillaPos,0,60,actor.getGameProfile().getName(),RollbackSelector.parse("blocks"));
            check(preview.blockChanges()==1&&level.getBlockState(vanillaPos).is(Blocks.STONE),"native_block_rollback_preview_changes_no_world_state");
            var rolled=NeoProtect.service().rollback(level,vanillaPos,0,60,actor.getGameProfile().getName(),RollbackSelector.parse("blocks"));
            check(rolled.blockChanges()==1&&level.getBlockState(vanillaPos).isAir(),"native_neoprotect_rollback_restores_previous_block_state");
            check(economy.balance(actor.getUUID(),currency)==balance,"world_block_rollback_preserves_independent_economy_balance");
            startCommerce(server);
        } catch(Throwable failure){org.slf4j.LoggerFactory.getLogger("interactions").error("[LOGGINGAUDIT] FAILED",failure);finish(server);}
    }
    private static void startCommerce(net.minecraft.server.MinecraftServer server) {
        var level=server.overworld(); actor.gameMode.changeGameModeForPlayer(GameType.CREATIVE);place(shopPos,new ItemStack(Items.CHEST));
        var stock=(Container)level.getBlockEntity(shopPos);stock.setItem(0,new ItemStack(Items.STONE,10));actor.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        var economy=net.yuuniverse.economy.api.YuuniverseEconomyApi.current().orElseThrow();
        var ledger=((net.yuuniverse.economy.EconomyRuntime)economy).ledger();currencyId=economy.defaultCurrency().id();
        var old=economy.coordinateShops().orElseThrow().shop(actor.getUUID(),new net.yuuniverse.economy.api.CoordinateShopReference.Position(level.dimension().location().toString(),shopPos.getX(),shopPos.getY(),shopPos.getZ()));
        if(old.isPresent())ledger.removeCoordinateShop(old.get().id(),"fixture repeat");
        var owner=economy.ensurePlayerAccount(UUID.randomUUID(),"Logging shop owner");
        var shop=ledger.createCoordinateShop(new net.yuuniverse.economy.core.CoordinateShopDraft(level.dimension().location().toString(),"fixture",shopPos.getX(),shopPos.getY(),shopPos.getZ(),owner.id(),
                "SELL_TO_PLAYER","{\"material\":\"STONE\"}","STONE",currencyId,10,1,false,10L,false,0,null),"fixture");
        shopId=shop.id(); actor.getInventory().clearContent();balanceBeforeTrade=economy.balance(actor.getUUID(),currencyId);
        tradeRequest=new net.yuuniverse.economy.core.CommerceRequest(net.yuuniverse.economy.core.CommerceShopType.COORDINATE,shopId,"",net.yuuniverse.economy.core.CommerceAction.BUY_FROM_SHOP,
                net.yuuniverse.economy.core.AccountIds.player(actor.getUUID()),2,"logging-trade:"+actor.getUUID(),"logging fixture");
        var api=economy.commerceApi().orElseThrow();tradeTerms=api.quote(actor.getUUID(),tradeRequest);tradeOutcome=api.execute(actor.getUUID(),tradeTerms);
        check(tradeOutcome.state()==net.yuuniverse.economy.api.CommerceOutcome.State.COMMITTED&&stock.getItem(0).getCount()==8&&actor.getInventory().countItem(Items.STONE)==2
                &&economy.balance(actor.getUUID(),currencyId)==balanceBeforeTrade-20,"actual_finite_shop_trade_settles_stock_items_and_money_with_both_loggers_loaded");
        stage=2;readyTick=server.getTickCount()+45;
    }
    private static void verifyCommerce(net.minecraft.server.MinecraftServer server) throws Exception {
        var level=server.overworld();var economy=net.yuuniverse.economy.api.YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();NeoProtect.service().flush();
        var receipt=tradeOutcome.receipt();
        var journal=api.history(actor.getUUID(),0,1000).stream().filter(e->e.receipt().operationId().equals(receipt.operationId())).toList();
        check(journal.size()==1&&journal.getFirst().shopId().equals(shopId)&&journal.getFirst().tradeQuantity()==2&&journal.getFirst().requestKey().equals(tradeRequest.idempotencyKey())
                &&journal.getFirst().receipt().transactionId().equals(receipt.transactionId()),"public_economy_history_correlates_exact_trade_request_operation_transaction_and_shop");
        check(NeoProtect.service().lookup(level,shopPos,0,100).stream().noneMatch(e->e.actionType().isContainerAction())
                &&Services.CONTAINER.getHistory(level,shopPos).isEmpty(),"programmatic_shop_stock_change_is_not_misreported_as_manual_container_take");
        var position=economy.coordinateShops().orElseThrow().shop(actor.getUUID(),new net.yuuniverse.economy.api.CoordinateShopReference.Id(shopId)).orElseThrow();
        check(position.blockX()==shopPos.getX()&&position.blockY()==shopPos.getY()&&position.blockZ()==shopPos.getZ(),"current_shop_lookup_exposes_physical_origin_separately_from_durable_receipt");
        var preview=NeoProtect.service().previewRollback(level,shopPos,0,60,actor.getGameProfile().getName(),RollbackSelector.parse("blocks"));
        check(preview.blockChanges()==1,"economic_container_block_rollback_has_explicit_world_scope");
        var rolled=NeoProtect.service().rollback(level,shopPos,0,60,actor.getGameProfile().getName(),RollbackSelector.parse("blocks"));
        check(rolled.blockChanges()==1&&level.getBlockState(shopPos).isAir(),"native_world_rollback_can_remove_shop_block_without_financial_reversal");
        check(economy.balance(actor.getUUID(),currencyId)==balanceBeforeTrade-20&&api.findOperation(actor.getUUID(),receipt.operationId()).orElseThrow().receipt().transactionId().equals(receipt.transactionId()),
                "shop_world_rollback_keeps_original_committed_economy_transaction");
        try {api.quote(actor.getUUID(),tradeRequest);throw new AssertionError("New quote for removed container");}
        catch(net.yuuniverse.economy.api.ShopApiException expected){check(true,"new_quote_after_world_rollback_rejects_unavailable_container");}
        var replay=api.execute(actor.getUUID(),tradeTerms);
        check(replay.receipt().operationId().equals(receipt.operationId())&&economy.balance(actor.getUUID(),currencyId)==balanceBeforeTrade-20&&actor.getInventory().countItem(Items.STONE)==2,
                "settled_trade_replay_after_shop_block_rollback_cannot_charge_or_deliver_twice");
        var state=new com.google.gson.JsonObject();state.addProperty("actor",actor.getUUID().toString());state.addProperty("currency",currencyId);state.addProperty("balance",balanceBeforeTrade-20);
        state.addProperty("operation",receipt.operationId().toString());state.addProperty("transaction",receipt.transactionId().toString());state.addProperty("shop",shopId);
        state.addProperty("run",System.getenv("CITIZENS_LOGGING_AUDIT_RUN"));
        var statePath=server.getServerDirectory().resolve("logging-restart-state.json");
        try(var stateFile=java.nio.channels.FileChannel.open(statePath,java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE)){
            var bytes=java.nio.charset.StandardCharsets.UTF_8.encode(state.toString());
            while(bytes.hasRemaining())stateFile.write(bytes);stateFile.force(true);
        }
        server.getPlayerList().saveAll();
        server.saveEverything(true,true,true);
        if("crash".equals(System.getenv("CITIZENS_LOGGING_AUDIT_PHASE"))){
            var run=System.getenv("CITIZENS_LOGGING_AUDIT_RUN");
            if(run==null||!run.matches("[0-9a-f]{32}"))throw new IllegalStateException("Missing logging crash run identity");
            org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGAUDIT] KILL_READY pid={} actor={} operation={} run={} checks={}",
                    ProcessHandle.current().pid(),actor.getUUID(),receipt.operationId(),run,passed);
            while(true)java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
        }
        org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGAUDIT] COMPLETE {} checks",passed);finish(server);
    }
    private static void recover(net.minecraft.server.MinecraftServer server) throws Exception {
        var state=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(server.getServerDirectory().resolve("logging-restart-state.json"))).getAsJsonObject();var level=server.overworld();
        var run=System.getenv("CITIZENS_LOGGING_AUDIT_RUN");
        if(run!=null&&!run.equals(state.get("run").getAsString()))throw new IllegalStateException("Different logging crash fixture run");
        actor=new ServerPlayer(server,level,new com.mojang.authlib.GameProfile(UUID.fromString(state.get("actor").getAsString()),"LoggingActor"),ClientInformation.createDefault());actor.setPos(9.5,-60,5.5);channel=connect(actor);
        var economy=net.yuuniverse.economy.api.YuuniverseEconomyApi.current().orElseThrow();var api=economy.commerceApi().orElseThrow();
        check(economy.balance(actor.getUUID(),state.get("currency").getAsString())==state.get("balance").getAsLong(),"native_restart_preserves_settled_balance_after_world_rollback");
        var operation=UUID.fromString(state.get("operation").getAsString());var receipt=api.findOperation(actor.getUUID(),operation).orElseThrow().receipt();
        check(receipt.transactionId().toString().equals(state.get("transaction").getAsString())&&api.history(actor.getUUID(),0,1000).stream().filter(e->e.receipt().operationId().equals(operation)).count()==1,
                "native_restart_retains_one_original_financial_receipt");
        check(level.getBlockState(shopPos).isAir()&&actor.getInventory().countItem(Items.STONE)==2,"native_restart_preserves_rolled_back_shop_world_and_original_delivered_items");
        check(NeoProtect.service().lookup(level,moddedPos,0,100).stream().anyMatch(e->e.actorUuid().equals(actor.getUUID())&&e.actionType()==ActionType.BLOCK_PLACE),"neoprotect_history_survives_native_server_restart");
        check(Services.BLOCK.getBlockHistory(level,moddedPos).stream().anyMatch(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&e.getAction()==BlockAction.PLACE_BLOCK),"grieflogger_independent_history_survives_native_server_restart");
        check(Services.CONTAINER.getHistory(level,chestPos).stream().anyMatch(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())&&e instanceof ItemHistory h&&h.getItemStack().getCount()==7
                &&h.getItemStack().toItemStack().getHoverName().getString().equals("Logging components")),"grieflogger_restart_retains_original_container_components");
        var restored=((Container)level.getBlockEntity(chestPos)).getItem(0);
        check(restored.getCount()==7&&restored.getHoverName().getString().equals("Logging components"),
                "native_restart_retains_container_rollback_components");
        check(NeoProtect.service().lookup(level,burstPos,0,1000).stream().filter(e->e.actorUuid().equals(actor.getUUID())
                    &&(e.actionType()==ActionType.BLOCK_PLACE||e.actionType()==ActionType.BLOCK_BREAK)).count()==BURST_PLACEMENTS*2
                &&Services.BLOCK.getBlockHistory(level,burstPos).stream().filter(e->e.getUser().getUuid().orElseThrow().equals(actor.getUUID())
                    &&(e.getAction()==BlockAction.PLACE_BLOCK||e.getAction()==BlockAction.BREAK_BLOCK)).count()==BURST_PLACEMENTS*2,
                "native_restart_retains_both_complete_burst_histories");
        org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGAUDIT] COMPLETE {} restart checks",passed);finish(server);
    }
    private static void place(BlockPos pos,ItemStack stack) {
        var level=(ServerLevel)actor.level();actor.setPos(pos.getX()+0.5,pos.getY(),pos.getZ()+2.5);level.removeBlock(pos,false);actor.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var hit=new BlockHitResult(Vec3.atCenterOf(pos.below()).add(0,0.5,0),Direction.UP,pos.below(),false);
        if(!actor.gameMode.useItemOn(actor,level,stack,InteractionHand.MAIN_HAND,hit).consumesAction())throw new AssertionError("Native placement rejected "+pos);
    }
    private static void finish(net.minecraft.server.MinecraftServer server){stage=99;if(actor!=null&&actor.connection!=null)server.getPlayerList().remove(actor);if(channel!=null)channel.finishAndReleaseAll();server.halt(false);}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;org.slf4j.LoggerFactory.getLogger("interactions").info("[LOGGINGAUDIT] PASS {}",name);}
    static io.netty.channel.embedded.EmbeddedChannel connect(ServerPlayer player) {
        var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        var channel=new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInitializer<io.netty.channel.Channel>() {
            @Override protected void initChannel(io.netty.channel.Channel channel) {
                net.minecraft.network.Connection.configureInMemoryPipeline(channel.pipeline(),net.minecraft.network.protocol.PacketFlow.SERVERBOUND);connection.configurePacketHandler(channel.pipeline());
            }
        });
        net.neoforged.neoforge.network.registration.NetworkRegistry.configureMockConnection(connection);
        var cookie=new net.minecraft.server.network.CommonListenerCookie(player.getGameProfile(),0,ClientInformation.createDefault(),false,net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
        connection.setupOutboundProtocol(net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(net.minecraft.network.RegistryFriendlyByteBuf.decorator(player.registryAccess(),cookie.connectionType())));
        player.getServer().getPlayerList().placeNewPlayer(connection,player,cookie);return channel;
    }
}
