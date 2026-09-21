package net.yuuniverse.interactions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.util.EntityPacketTracker;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "interactions")
public final class DialogueDisplayRuntimeAudit {
    private static boolean forced, finished;
    private static int passed;
    private static State state;
    private static ScheduledActionRuntimeAudit scheduled;
    private static InfluenceRuntimeAudit influence;
    private static WorldResolutionRuntimeAudit worlds;
    private static ServerTransferRuntimeAudit transfers;

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        var server = event.getServer(); var level = server.overworld();
        if (!forced) { level.setChunkForced(0, 0, true); forced = true; }
        if (!level.areEntitiesLoaded(ChunkPos.asLong(0, 0)) || !level.isPositionEntityTicking(new BlockPos(1, 0, 1))) {
            if (server.getTickCount() > 1200) {
                finished = true; LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED fixture loading"); server.halt(false);
            }
            return;
        }
        try {
            if (state == null) {
                state = new State(server);
                state.run();
                scheduled = new ScheduledActionRuntimeAudit(server, state::player);
                scheduled.start();
                influence = new InfluenceRuntimeAudit(server, state::player);
                influence.start();
            } else if (transfers != null) {
                if (transfers.tick(event)) {
                    finished = true;
                    LoggerFactory.getLogger("interactions").info("[DIALOGUEDISPLAYAUDIT] COMPLETE {} checks", passed);
                }
            } else if (worlds != null) {
                if (worlds.tick(event)) {
                    transfers = new ServerTransferRuntimeAudit(server, state::proxyPlayer);
                    transfers.start();
                }
            } else {
                boolean influenceDone = influence.tick(event);
                if (scheduled.tick(event) && influenceDone) {
                    worlds = new WorldResolutionRuntimeAudit(server, state::player);
                    worlds.start();
                }
            }
        } catch (Throwable failure) {
            finished = true;
            LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED", failure);
        } finally {
            if (finished) {
                try { if (transfers != null) transfers.close(); } catch (Throwable failure) { LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED transfer cleanup", failure); }
                try { if (worlds != null) worlds.close(); } catch (Throwable failure) { LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED world cleanup", failure); }
                try { if (state != null) state.close(); } catch (Throwable failure) { LoggerFactory.getLogger("interactions").error("[DIALOGUEDISPLAYAUDIT] FAILED cleanup", failure); }
                server.halt(false);
            }
        }
    }

    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final List<AuditPlayer> players = new ArrayList<>();
        final List<Session> sessions = new ArrayList<>();
        AuditPlayer alice, bob;
        Entity npc;
        State(MinecraftServer server) { this.server = server; level = server.overworld(); }

        void run() throws Exception {
            if (!Files.exists(Path.of("dialogue-display-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("Display audit requires its own empty fixture");
            alice = player("DisplayAlice"); bob = player("DisplayBob");
            NativeActionRuntimeAudit.run(alice, bob);
            npc = EntityType.VILLAGER.create(level); npc.setPos(3, -60, 1);
            var engine = new EngineState();
            Conversation story = story();
            var a = session(engine, story, alice);
            var b = session(new EngineState(), story, bob);
            alice.clear(); bob.clear();
            a.tick(); alice.pump(); bob.pump();
            ServerBossEvent bar = bar(a); UUID barId = bar.getId();
            check(bar.getPlayers().equals(Set.of(alice)), "bossbar_links_only_session_owner");
            check(bar.getName().getString().equals("Talk: Speaker") && bar.getColor() == BossEvent.BossBarColor.BLUE
                    && bar.getOverlay() == BossEvent.BossBarOverlay.NOTCHED_10 && bar.getProgress() == 1,
                    "legacy_bossbar_style_title_and_constant_progress");
            check(alice.bars.containsKey(barId) && !bob.bars.containsKey(barId), "bossbar_packet_is_private");
            check(alice.chat.stream().map(Component::getString).anyMatch(text -> text.contains("Hello DisplayAlice")),
                    "hologram_does_not_replace_chat_delivery");
            List<ArmorStand> first = holograms(a);
            check(first.size() == 3, "empty_hologram_rows_reserve_space_without_an_entity");
            check(first.get(0).getCustomName().getString().equals("Hello DisplayAlice")
                    && first.get(1).getCustomName().getString().equals("Line ")
                    && first.get(2).getCustomName().getString().equals("Tail"), "hologram_placeholders_and_control_markers");
            check(first.stream().allMatch(entity -> entity.isMarker() && entity.isInvisible() && entity.isNoGravity()
                    && entity.isCustomNameVisible() && level.getEntity(entity.getId()) == null), "holograms_are_virtual_marker_nameplates");
            double top = -60 + 2.7 + 3 * 0.2;
            for (int i = 0; i < first.size(); i++) {
                int row = i == 2 ? 3 : i;
                var stand = first.get(i);
                double shownY = stand.getY() + stand.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, stand.getYRot()).y + 0.5;
                check(Math.abs(shownY - (top - row * 0.2)) < 1E-6, "hologram_nameplate_anchor_row_" + row);
            }
            Set<Integer> firstIds = ids(first);
            check(alice.spawnIds().containsAll(firstIds) && bob.spawnIds().stream().noneMatch(firstIds::contains), "hologram_spawn_packets_are_private");
            AuditPlayer late = player("DisplayLate"); late.pump();
            check(late.spawnIds().stream().noneMatch(firstIds::contains), "late_joiner_does_not_receive_private_holograms");

            b.tick(); bob.pump();
            check(!bar(b).getId().equals(barId) && !ids(holograms(b)).equals(firstIds), "simultaneous_sessions_own_distinct_display_ids");
            check(holograms(b).getFirst().getCustomName().getString().equals("Hello DisplayBob"), "simultaneous_text_uses_each_player_context");
            a.skipDialogue(false); a.tick(); a.tick();
            check(a.isAwaitingChoice() && bar.getName().getString().equals("Choose: Speaker") && bar.getProgress() == 1,
                    "options_switch_bossbar_title_and_fill_progress");
            alice.clear(); a.cycleSelection(1, System.currentTimeMillis()); alice.pump();
            check(ids(holograms(a)).equals(firstIds) && alice.spawnIds().isEmpty(), "option_redraw_keeps_existing_hologram_entities");
            check(a.choose(1), "dialogue_choice_accepted");
            a.tick(); alice.pump();
            check(bar.getName().getString().equals("Talk: Speaker") && holograms(a).size() == 1,
                    "next_node_updates_both_displays");
            check(alice.removedIds().containsAll(firstIds) && first.stream().allMatch(Entity::isRemoved), "replaced_holograms_are_removed");
            Set<Integer> finalIds = ids(holograms(a));
            a.end(true); alice.pump();
            check(!alice.bars.containsKey(barId) && alice.removedIds().containsAll(finalIds) && holograms(a).isEmpty(), "normal_end_clears_bossbar_and_hologram");
            check(!b.isFinished() && !holograms(b).isEmpty() && bar(b) != null, "ending_one_session_preserves_another");
            a.end(false); check(holograms(a).isEmpty(), "display_cleanup_is_idempotent");

            timed();
            writing();
            statusBar();
            writingRespawn();
            inlineChoices();
            inlineSelectionAndRoutes();
            interruptions();
            offsetsAndFailures();
            controllerCleanup();
            bob.setPos(30, -60, 1); b.tick(); bob.pump();
            check(b.isFinished() && holograms(b).isEmpty() && bar(b) == null, "range_exit_clears_displays");
            respawn();
        }

        void writing() throws Exception {
            var engine = new EngineState(); engine.settings = animated(WriteDialogueSettings.Mode.CHARACTER, 2, true);
            Conversation story = story(); var line = story.first().lines.getFirst();
            line.time = -1; line.text.clear(); line.text.add("&aA😀 %next%");
            line.actions.add("player_command_as_op: give @s minecraft:paper 1");
            line.lastActions.add("player_command_as_op: give @s minecraft:diamond 1");
            alice.getInventory().clearContent();
            Session session = session(engine, story, alice); alice.clear(); bob.clear(); session.tick(); alice.pump(); bob.pump();
            check(alice.chat.size() >= 21 && alice.chat.get(20).getString().equals("A")
                    && alice.chat.subList(0, 19).stream().allMatch(c -> c.getString().isEmpty()),
                    "writer_starts_one_character_with_nineteen_blank_lines_and_heading");
            check(alice.actionBars().equals(List.of("Status: Speaker")) && bob.actionBars().isEmpty() && bob.chat.isEmpty(),
                    "writer_and_actionbar_packets_are_private");
            check(holograms(session).getFirst().getCustomName().getString().equals("A😀 "), "hologram_keeps_full_body_independent_of_chat_animation");
            check(alice.getInventory().countItem(Items.PAPER) == 1, "writer_initial_actions_execute_once");
            alice.clear(); session.tick(); alice.pump();
            check(alice.chat.isEmpty(), "writer_respects_intermediate_delay_tick");
            session.tick(); alice.pump();
            check(alice.chat.getLast().getString().equals("A😀"), "writer_sends_complete_unicode_codepoint");
            alice.clear();
            check(session.skipDialogue(false) && !session.skipDialogue(false), "skip_during_writing_is_queued_once");
            session.tick(); session.tick(); alice.pump();
            check(session.isAwaitingChoice() && alice.getInventory().countItem(Items.DIAMOND) == 1
                    && alice.getInventory().countItem(Items.PAPER) == 1, "skip_during_writing_completes_actions_without_replaying_initial_actions");
            check(alice.actionBars().getLast().equals("Pick: Speaker"), "options_switch_actionbar_immediately");
            alice.clear(); session.cycleSelection(1, System.currentTimeMillis()); alice.pump();
            check(alice.chat.stream().anyMatch(c -> c.getString().equals("A😀 [Next →]")), "selection_redraw_uses_full_completed_text");
            alice.clear(); for (int i = 0; i < 25; i++) session.tick(); alice.pump();
            check(alice.chat.isEmpty() && alice.getInventory().countItem(Items.DIAMOND) == 1, "writer_cannot_overwrite_options_or_repeat_rewards");
            session.choose(1); alice.clear(); session.tick(); alice.pump();
            check(alice.actionBars().getLast().equals("Status: Speaker") && alice.chat.getLast().getString().equals("A"),
                    "next_node_starts_fresh_writer_and_speaking_status");
            session.end(false); alice.clear(); for (int i = 0; i < 30; i++) session.tick(); alice.pump();
            check(alice.chat.isEmpty() && alice.actionBars().isEmpty(), "ended_session_sends_no_delayed_frames_or_status");

            var fast = new EngineState(); fast.settings = animated(WriteDialogueSettings.Mode.WORD, 1, false);
            Conversation words = story(); var wordLine = words.first().lines.getFirst();
            wordLine.time = -1; wordLine.showName = false; wordLine.text.clear();
            wordLine.text.addAll(List.of("  One  two", "", "Tail %next%"));
            Session wordSession = session(fast, words, alice); alice.clear(); wordSession.tick(); alice.pump();
            check(alice.chat.size() == 20 && alice.chat.getLast().getString().equals("  One"), "word_mode_preserves_leading_spaces_and_show_name_false");
            alice.clear(); wordSession.tick(); alice.pump();
            check(alice.chat.getLast().getString().equals("  One  two"), "word_mode_preserves_repeated_spaces");
            for (int i = 0; i < 5; i++) wordSession.tick(); alice.pump();
            check(alice.chat.getLast().getString().equals("Tail [Next →]") && alice.chat.getLast().toFlatList().stream()
                    .anyMatch(c -> c.getStyle().getClickEvent() != null && c.getStyle().getHoverEvent() != null),
                    "multiline_writer_finishes_with_functional_next_control");
            alice.clear(); for (int i = 0; i < 40; i++) wordSession.tick(); alice.pump();
            check(alice.chat.isEmpty() && !wordSession.isAwaitingChoice() && !wordSession.isFinished(), "writer_completion_does_not_complete_manual_dialogue");
            wordSession.end(false);

            Conversation timed = story(); timed.first().lines.getFirst().time = 0.1;
            var slow = new EngineState(); slow.settings = animated(WriteDialogueSettings.Mode.CHARACTER, 50, true);
            Session expires = session(slow, timed, alice); expires.tick(); expires.tick(); expires.tick(); expires.tick();
            check(expires.isAwaitingChoice(), "dialogue_timer_does_not_wait_for_slow_writer");
            alice.clear(); for (int i = 0; i < 55; i++) expires.tick(); alice.pump();
            check(alice.chat.isEmpty(), "timed_completion_cancels_writer_before_it_can_erase_options"); expires.end(false);

            Conversation sequential = story(); sequential.first().options.clear();
            sequential.first().lines.getFirst().time = 0.05;
            var second = new Conversation.Line(); second.time = -1; second.text.add("Second %next%"); sequential.first().lines.add(second);
            Session lines = session(slow, sequential, alice); lines.tick(); lines.tick(); alice.clear(); lines.tick(); alice.pump();
            check(alice.chat.getLast().getString().equals("S"), "next_sequential_line_replaces_unfinished_writer"); lines.end(false);

            Conversation invalid = story(); invalid.first().lines.getFirst().text.add("json:{broken");
            Session failure = session(engine, invalid, alice); alice.clear(); failure.tick(); failure.tick(); alice.pump();
            check(failure.isFinished() && alice.actionBars().isEmpty() && holograms(failure).isEmpty(), "invalid_animated_text_never_leaks_a_status_or_writer");

            Conversation rejected = story(); rejected.first().lines.getFirst().lastActions.add("player_command_as_op: clear @s minecraft:emerald 1");
            Session actionFailure = session(engine, rejected, alice); actionFailure.tick(); alice.clear();
            actionFailure.skipDialogue(false); actionFailure.tick(); alice.pump();
            check(actionFailure.isFinished() && alice.actionBars().equals(List.of("")) && field(actionFailure, "writer") == null,
                    "failed_completion_action_cancels_writer_and_clears_status");
        }

        void statusBar() throws Exception {
            var engine = new EngineState(); engine.settings = animated(WriteDialogueSettings.Mode.WORD, 2, true);
            Conversation story = story(); story.first().lines.getFirst().time = -1;
            Session session = session(engine, story, alice); alice.clear(); session.tick(); alice.pump();
            check(alice.actionBars().equals(List.of("Status: Speaker")), "actionbar_uses_conversation_title_during_speech");
            alice.clear(); for (int i = 0; i < 19; i++) session.tick(); alice.pump();
            check(alice.actionBars().isEmpty(), "actionbar_does_not_send_every_tick");
            session.tick(); alice.pump(); check(alice.actionBars().equals(List.of("Status: Speaker")), "actionbar_refreshes_at_twenty_ticks");
            engine.settings = animated(WriteDialogueSettings.Mode.WORD, 2, false); alice.clear(); session.tick(); alice.pump();
            check(alice.actionBars().equals(List.of("")), "disabling_actionbar_clears_existing_status");
            alice.clear(); session.tick(); alice.pump(); check(alice.actionBars().isEmpty(), "disabled_actionbar_does_not_repeatedly_clear_other_hud_text");
            engine.settings = animated(WriteDialogueSettings.Mode.WORD, 2, true); session.tick(); alice.pump();
            check(alice.actionBars().equals(List.of("Status: Speaker")), "reenabling_actionbar_uses_current_phase");
            Conversation other = story(); other.name = "Other";
            Session parallel = session(engine, other, bob); bob.clear(); parallel.tick(); bob.pump();
            check(bob.actionBars().equals(List.of("Status: Other")), "simultaneous_actionbars_use_each_session_title");
            bob.clear();
            alice.clear(); session.end(false); session.end(false); alice.pump();
            check(alice.actionBars().equals(List.of("")), "actionbar_end_cleanup_is_idempotent");
            check(bob.actionBars().isEmpty() && !parallel.isFinished(), "ending_one_actionbar_does_not_clear_another_players_status");
            parallel.end(false);

            Session dimension = session(engine, story, alice); dimension.tick();
            alice.teleportTo(server.getLevel(Level.NETHER), 1, 64, 1, Set.of(), 0, 0); alice.clear(); dimension.tick(); alice.pump();
            check(dimension.isFinished() && alice.actionBars().equals(List.of("")) && field(dimension, "writer") == null,
                    "dimension_exit_cancels_writer_and_actionbar");
            alice.teleportTo(level, 1, -60, 1, Set.of(), 0, 0);
        }

        void writingRespawn() throws Exception {
            AuditPlayer viewer = player("WritingRespawn");
            var engine = new EngineState(); engine.settings = animated(WriteDialogueSettings.Mode.CHARACTER, 1, true);
            Conversation story = story(); story.first().lines.getFirst().time = -1;
            story.first().lines.getFirst().text.clear(); story.first().lines.getFirst().text.add("ABC %next%");
            Session session = session(engine, story, viewer); session.tick(); viewer.clear();
            ServerPlayer replacement = server.getPlayerList().respawn(viewer, false, Entity.RemovalReason.KILLED);
            replacement.setPos(1, -60, 1); session.tick(); viewer.pump();
            check(session.player() == replacement && viewer.chat.getLast().getString().equals("AB"), "respawn_preserves_writer_position_on_new_player");
            check(viewer.actionBars().getLast().equals("Status: Speaker"), "respawn_resends_actionbar_to_replacement_connection");
            replacement.setPos(30, -60, 1); viewer.clear(); session.tick(); viewer.pump();
            check(session.isFinished() && viewer.actionBars().equals(List.of("")) && viewer.chat.isEmpty(), "range_exit_cancels_writer_and_clears_actionbar");
            AuditPlayer distant = player("WritingDistant");
            Session invalid = session(engine, story, distant); invalid.tick(); distant.clear();
            ServerPlayer outside = server.getPlayerList().respawn(distant, false, Entity.RemovalReason.KILLED);
            outside.setPos(30, -60, 1); invalid.tick(); distant.pump();
            check(invalid.isFinished() && distant.actionBars().equals(List.of("")), "invalid_respawn_clears_owned_status_without_resending_it");
        }

        @SuppressWarnings("unchecked")
        void inlineChoices() throws Exception {
            var engine = new EngineState();
            engine.messages = new DialogueMessages(null, null, "[%number%] %text%", "Choose %option%", List.of("Separate menu", "%options%"));
            Conversation story = inlineStory(); story.blockMovement = false;
            var node = story.first(); var line = node.lines.getFirst();
            line.text.clear(); line.text.addAll(List.of("Intro", "Before %option_1% | %option_2% after", "%option_3%", "Done %next% suffix"));
            node.options.clear();
            var hidden = new Conversation.Option(); hidden.text = "Hidden"; hidden.requires.add("yes == no"); node.options.add(hidden);
            var temporary = new Conversation.Option(); temporary.text = "Temporary"; temporary.requires.add("%player_level% == 0"); node.options.add(temporary);
            var stable = new Conversation.Option(); stable.text = "Stable %player%"; stable.actions.add("player_command_as_op: give @s minecraft:emerald 1"); node.options.add(stable);
            line.lastActions.add("player_command_as_op: experience add @s 1 levels");
            node.interruptActions.add("player_command_as_op: give @s minecraft:diamond 1");
            alice.experienceLevel = 0; alice.getInventory().clearContent();
            var controller = new InteractionsMod(); NeoForge.EVENT_BUS.unregister(controller);
            controller.onRegisterCommands(new RegisterCommandsEvent(server.getCommands().getDispatcher(), Commands.CommandSelection.DEDICATED,
                    CommandBuildContext.simple(server.registryAccess(), FeatureFlags.DEFAULT_FLAGS)));
            Map<UUID, Session> managed = (Map<UUID, Session>) field(controller, "sessions");
            Session session = session(engine, story, alice); managed.put(alice.getUUID(), session); alice.clear(); session.tick(); alice.pump();
            check(alice.chat.stream().anyMatch(c -> c.getString().equals("Before [1] Temporary | [2] Stable DisplayAlice after")),
                    "inline_preview_uses_filtered_positions_and_preserves_multiple_markers_and_suffix");
            check(alice.chat.stream().noneMatch(c -> c.getString().contains("%option_") || c.getString().contains("Hidden")),
                    "unavailable_inline_rows_do_not_leak_markers_or_hidden_options");
            check(alice.chat.stream().anyMatch(c -> c.getString().equals("Done [Next →] suffix")), "next_control_preserves_following_text");
            check(holograms(session).stream().anyMatch(h -> h.getCustomName().getString().contains("[1] Temporary | [2] Stable DisplayAlice")),
                    "inline_hologram_displays_resolved_option_labels");
            String preview = choices(alice).getFirst();
            check(server.getCommands().getDispatcher().execute(preview.substring(1), alice.createCommandSourceStack()) == 0,
                    "inline_preview_click_cannot_bypass_line_completion");
            check(!DialogueCommands.isBlocked(alice.getUUID(), preview.substring(1)), "view_bound_choice_is_allowed_through_command_restrictions");
            session.skipDialogue(false); session.tick(); alice.clear(); session.tick(); alice.pump();
            check(session.isAwaitingChoice() && session.offeredCount() == 1 && alice.experienceLevel == 1,
                    "inline_options_refresh_after_completion_actions_change_requirements");
            check(alice.chat.stream().anyMatch(c -> c.getString().equals("Before [1] Stable DisplayAlice |  after"))
                    && alice.chat.stream().noneMatch(c -> c.getString().contains("Separate menu")), "inline_ready_phase_redraws_body_without_separate_menu");
            check(server.getCommands().getDispatcher().execute(preview.substring(1), alice.createCommandSourceStack()) == 0,
                    "stale_preview_cannot_select_a_different_option_after_renumbering");
            String ready = choices(alice).getFirst();
            check(!ready.equals(preview) && server.getCommands().getDispatcher().execute(ready.substring(1), alice.createCommandSourceStack()) == 1,
                    "current_inline_packet_command_selects_the_displayed_option");
            check(server.getCommands().getDispatcher().execute(ready.substring(1), alice.createCommandSourceStack()) == 0,
                    "inline_double_click_cannot_queue_twice");
            session.tick();
            check(session.isFinished() && alice.getInventory().countItem(Items.EMERALD) == 1
                    && alice.getInventory().countItem(Items.DIAMOND) == 0, "normal_inline_completion_rewards_once_without_interrupt_actions");

            line.lastActions.clear();
            Session alias = session(engine, story, alice); managed.put(alice.getUUID(), alias);
            alias.tick(); alias.skipDialogue(false); alias.tick(); alias.tick();
            check(server.getCommands().getDispatcher().execute("interactions useoption 1", alice.createCommandSourceStack()) == 1,
                    "original_useoption_alias_selects_current_inline_choice"); alias.tick();

            stable.requires.add("%player_level% == 1");
            Session revoked = session(engine, story, alice); revoked.tick(); revoked.skipDialogue(false); revoked.tick(); revoked.tick();
            alice.experienceLevel = 2;
            check(!revoked.choose(1), "inline_choice_rechecks_requirements_at_input");
            check(!Conditions.all(List.of("%external_%player_level%% == %external_2%"), alice),
                    "partly_resolved_unknown_placeholder_cannot_satisfy_a_condition");
            alice.experienceLevel = 1; check(revoked.chooseByText("1"), "inline_choices_remain_available_through_typed_numbers");
            alice.experienceLevel = 2; int emeralds = alice.getInventory().countItem(Items.EMERALD); revoked.tick();
            check(revoked.isFinished() && alice.getInventory().countItem(Items.EMERALD) == emeralds
                    && alice.getInventory().countItem(Items.DIAMOND) == 1, "revoked_pending_choice_interrupts_without_option_reward");
            managed.clear();
        }

        void inlineSelectionAndRoutes() throws Exception {
            var engine = new EngineState(); engine.settings = animated(WriteDialogueSettings.Mode.CHARACTER, 1, true);
            engine.messages = new DialogueMessages(null, null, "[%number%] %text%", null, List.of("Separate menu", "%options%"),
                    "N%number% %text%", "S%number% %text%");
            Conversation story = inlineStory(); var line = story.first().lines.getFirst();
            line.text.clear(); line.text.addAll(List.of("%option_1%", "%option_2%")); line.time = 0.05;
            Session session = session(engine, story, alice); alice.clear(); session.tick(); alice.pump();
            check(alice.chat.getLast().getString().equals("S1 Next"), "nonclickable_inline_selection_is_an_atomic_writer_control");
            session.tick(); session.tick(); alice.clear(); session.cycleSelection(1, System.currentTimeMillis()); alice.pump();
            check(alice.chat.stream().anyMatch(c -> c.getString().equals("N1 Next"))
                    && alice.chat.stream().anyMatch(c -> c.getString().equals("S2 Stop")) && choices(alice).isEmpty(),
                    "movement_selection_redraws_inline_highlight_without_click_events");
            check(alice.chat.stream().noneMatch(c -> c.getString().equals("Separate menu")), "inline_selection_does_not_duplicate_options_block");
            alice.clear(); for (int i = 0; i < 25; i++) session.tick(); alice.pump();
            check(alice.chat.isEmpty(), "inline_selection_redraw_does_not_restart_writer");
            check(session.confirmSelection(), "inline_sneak_confirmation_queues_selected_option"); session.tick();
            check(session.isFinished(), "inline_selected_terminal_option_ends_normally");

            var plain = new EngineState();
            Conversation routed = inlineStory(); routed.blockMovement = false;
            var target = new Conversation.Node("elsewhere"); routed.nodes.put(target.key, target);
            for (int i = 1; i <= 10; i++) { var option = new Conversation.Option(); option.text = "Target " + i; target.options.add(option); }
            target.options.get(9).actions.add("player_command_as_op: give @s minecraft:gold_ingot 1");
            var routedLine = routed.first().lines.getFirst(); routedLine.startOptions = target.key; routedLine.time = 0.05;
            routedLine.text.clear(); routedLine.text.add("%option_10%");
            Session route = session(plain, routed, alice); alice.clear(); route.tick(); alice.pump();
            check(alice.chat.stream().anyMatch(c -> c.getString().equals("[10] Target 10")), "inline_start_options_resolves_target_node_and_tenth_option");
            route.tick(); route.tick(); check(route.choose(10), "inline_target_option_can_be_selected"); route.tick();
            check(alice.getInventory().countItem(Items.GOLD_INGOT) == 1, "inline_start_options_executes_target_reward");

            Conversation skipped = inlineStory(); skipped.blockMovement = false;
            skipped.first().lines.getFirst().time = 0.05;
            var excluded = new Conversation.Line(); excluded.requires.add("yes == no"); excluded.text.add("Excluded"); skipped.first().lines.add(excluded);
            Session fallback = session(plain, skipped, alice); fallback.tick(); fallback.tick(); alice.clear(); fallback.tick(); alice.pump();
            check(fallback.isAwaitingChoice() && !choices(alice).isEmpty(), "skipped_terminal_line_uses_last_completed_body_for_inline_options"); fallback.end(false);

            Conversation random = inlineStory(); random.blockMovement = false; random.first().randomDialogue = true;
            var alternate = new Conversation.Line(); alternate.time = 0.05; alternate.text.add("Other %option_1%"); random.first().lines.add(alternate);
            Session randomized = session(plain, random, alice); alice.clear(); randomized.tick(); alice.pump();
            check(!choices(alice).isEmpty(), "random_dialogue_previews_its_inline_options"); randomized.end(false);

            Conversation invalid = inlineStory(); invalid.first().lines.getFirst().text.clear(); invalid.first().lines.getFirst().text.add("%option_0%");
            invalid.first().lines.getFirst().actions.add("player_command_as_op: give @s minecraft:iron_ingot 1");
            Session malformed = session(plain, invalid, alice); malformed.tick();
            check(malformed.isFinished() && alice.getInventory().countItem(Items.IRON_INGOT) == 0,
                    "invalid_inline_index_fails_before_initial_rewards");
        }

        void interruptions() throws Exception {
            var engine = new EngineState(); engine.settings = animated(WriteDialogueSettings.Mode.CHARACTER, 2, true);
            Conversation story = story(); story.first().interruptActions.add("player_command_as_op: give @s minecraft:paper 1");
            story.first().lines.getFirst().lastActions.add("player_command_as_op: give @s minecraft:emerald 1");
            alice.getInventory().clearContent();
            Session cancelled = session(engine, story, alice); cancelled.tick(); cancelled.end(false); cancelled.end(false); cancelled.tick();
            check(alice.getInventory().countItem(Items.PAPER) == 1 && alice.getInventory().countItem(Items.EMERALD) == 0,
                    "interrupt_actions_execute_once_without_line_completion_actions");
            check(!DialogueCommands.isBlocked(alice.getUUID(), "say test") && bar(cancelled) == null && holograms(cancelled).isEmpty(),
                    "interrupt_end_releases_owned_controls_and_displays");
            Session waiting = session(engine, story, alice); waiting.tick(); waiting.skipDialogue(false); waiting.tick(); waiting.tick(); waiting.end(false);
            check(alice.getInventory().countItem(Items.PAPER) == 2 && alice.getInventory().countItem(Items.EMERALD) == 1,
                    "interrupt_while_choosing_does_not_repeat_completed_last_actions");
            Session beforeTick = session(engine, story, alice); beforeTick.end(false);
            check(alice.getInventory().countItem(Items.PAPER) == 3, "interrupt_before_first_tick_uses_current_node");

            Conversation routed = story(); routed.first().interruptActions.add("player_command_as_op: give @s minecraft:iron_ingot 1");
            routed.first().lines.getFirst().startConversation = "conversation2";
            routed.node("conversation2").interruptActions.add("player_command_as_op: give @s minecraft:diamond 1");
            Session next = session(engine, routed, alice); next.tick(); next.skipDialogue(false); next.tick(); next.tick(); next.end(false);
            check(alice.getInventory().countItem(Items.IRON_INGOT) == 0 && alice.getInventory().countItem(Items.DIAMOND) == 1,
                    "interrupt_actions_follow_current_node_after_routing");

            Conversation recursive = story();
            Session reentrant = session(engine, recursive, alice);
            server.getCommands().getDispatcher().register(Commands.literal("audit_interrupt_reenter").executes(context -> {
                reentrant.end(false); return 1;
            }));
            recursive.first().interruptActions.addAll(List.of("console_command: audit_interrupt_reenter", "player_command_as_op: give @s minecraft:paper 1"));
            reentrant.tick(); reentrant.end(false);
            check(alice.getInventory().countItem(Items.PAPER) == 4, "interrupt_command_reentrancy_cannot_repeat_actions");

            Conversation invalid = story(); invalid.first().interruptActions.addAll(List.of("player_command_as_op: give @s minecraft:paper 1", "missing_verb: invalid"));
            Session bad = session(engine, invalid, alice); bad.tick(); bad.end(false);
            check(bad.isFinished() && bar(bad) == null && alice.getInventory().countItem(Items.PAPER) == 4,
                    "invalid_interrupt_batch_is_preflighted_and_cleanup_still_completes");

            Conversation teleport = story(); teleport.first().interruptActions.add("teleport: minecraft:overworld;1;-60;2;0;0");
            Session freed = session(engine, teleport, alice); freed.tick(); freed.end(false);
            check(alice.getZ() == 2, "interrupt_teleport_runs_after_movement_restrictions_are_released"); alice.setPos(1, -60, 1);

            Session range = session(engine, story, alice); range.tick(); alice.setPos(30, -60, 1); range.tick();
            check(range.isFinished() && alice.getInventory().countItem(Items.PAPER) == 5, "range_exit_runs_current_interrupt_actions"); alice.setPos(1, -60, 1);

            AuditPlayer old = player("InterruptRespawn");
            Conversation respawnStory = story(); respawnStory.first().interruptActions.add("player_command_as_op: give @s minecraft:gold_ingot 1");
            Session respawn = session(engine, respawnStory, old); respawn.tick(); old.clear();
            ServerPlayer replacement = server.getPlayerList().respawn(old, false, Entity.RemovalReason.KILLED);
            respawn.end(false); old.pump();
            check(respawn.player() == replacement && replacement.getInventory().countItem(Items.GOLD_INGOT) == 1
                    && old.getInventory().countItem(Items.GOLD_INGOT) == 0, "interrupt_before_next_tick_targets_replacement_player_after_respawn");
            check(old.actionBars().equals(List.of("")), "interrupt_after_respawn_clears_existing_status_without_refresh");
        }

        void timed() throws Exception {
            var engine = new EngineState();
            engine.settings = settings(new BossBarSettings(true, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS, true));
            Conversation story = story(); story.first().lines.getFirst().time = 4;
            Session timed = session(engine, story, alice); timed.tick();
            check(bar(timed).getProgress() == 0 && bar(timed).getColor() == BossEvent.BossBarColor.RED
                    && bar(timed).getOverlay() == BossEvent.BossBarOverlay.PROGRESS, "timed_bossbar_starts_empty_with_configured_style");
            for (int i = 0; i < 19; i++) timed.tick();
            check(bar(timed).getProgress() == 0, "timed_bossbar_waits_for_second_boundary");
            timed.tick(); check(bar(timed).getProgress() == 0.25F, "timed_bossbar_advances_at_one_second");
            for (int i = 0; i < 20; i++) timed.tick();
            check(bar(timed).getProgress() == 0.5F, "timed_bossbar_progress_tracks_current_line");
            timed.skipDialogue(false); timed.tick(); timed.tick();
            check(timed.isAwaitingChoice() && bar(timed).getProgress() == 1, "manual_skip_enters_full_options_bar");
            engine.settings = settings(new BossBarSettings(false, BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS, true));
            timed.tick(); check(bar(timed) == null, "disabled_bossbar_is_removed");
            engine.settings = settings(new BossBarSettings(true, BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_6, true));
            timed.tick(); check(bar(timed).getProgress() == 1 && bar(timed).getColor() == BossEvent.BossBarColor.GREEN,
                    "reenabled_bossbar_uses_current_session_phase");
            timed.end(false);

            Conversation manual = story(); manual.first().lines.getFirst().time = -1;
            Session waiting = session(engine, manual, alice); waiting.tick();
            for (int i = 0; i < 50; i++) waiting.tick();
            check(!waiting.isAwaitingChoice() && bar(waiting).getProgress() == 0, "indefinite_line_does_not_fabricate_timer_progress");
            waiting.end(false);
        }

        void offsetsAndFailures() throws Exception {
            Conversation shifted = story(); shifted.hologram = new HologramSettings(true, 3.5, 1.25);
            Session offset = session(new EngineState(), shifted, alice); offset.tick();
            ArmorStand line = holograms(offset).getFirst();
            check(Math.abs(line.getX() - 3) < 1E-6 && Math.abs(line.getZ() - 2.25) < 1E-6, "horizontal_offset_uses_viewer_relative_direction");
            double oldX = line.getX(); npc.setPos(4, -60, 1);
            offset.tick(); check(holograms(offset).getFirst().getX() == oldX, "hologram_anchor_stays_at_conversation_start");
            offset.end(false); npc.setPos(3, -60, 1);

            Conversation invalid = story(); invalid.first().lines.getFirst().text.add("json:{broken");
            Session failure = session(new EngineState(), invalid, alice); failure.tick(); alice.pump();
            check(failure.isFinished() && bar(failure) == null && holograms(failure).isEmpty(), "render_failure_cleans_displays");
            Conversation json = story(); json.first().lines.getFirst().text.clear();
            json.first().lines.getFirst().text.add("json:{\"text\":\"JSON display\",\"color\":\"gold\"}");
            Session rich = session(new EngineState(), json, alice); rich.tick();
            check(holograms(rich).getFirst().getCustomName().getString().equals("JSON display"), "json_hologram_uses_component_content");
            rich.end(false);
            Conversation plain = story(); plain.hologram = HologramSettings.DEFAULT;
            Session noHologram = session(new EngineState(), plain, alice); noHologram.tick();
            check(holograms(noHologram).isEmpty() && bar(noHologram) != null, "disabled_hologram_does_not_disable_bossbar");
            noHologram.end(false);
            Session noNpc = new Session(new EngineState(), story(), story().first(), alice, null); sessions.add(noNpc); noNpc.tick();
            check(holograms(noNpc).isEmpty() && bar(noNpc) != null, "missing_npc_has_no_hologram_anchor"); noNpc.end(false);
            Session dimension = session(new EngineState(), story(), alice); dimension.tick();
            alice.teleportTo(server.getLevel(Level.NETHER), 1, 64, 1, Set.of(), 0, 0); dimension.tick();
            check(dimension.isFinished() && bar(dimension) == null && holograms(dimension).isEmpty(), "dimension_change_clears_displays");
            alice.teleportTo(level, 1, -60, 1, Set.of(), 0, 0);
        }

        @SuppressWarnings("unchecked")
        void controllerCleanup() throws Exception {
            var controller = new InteractionsMod(); NeoForge.EVENT_BUS.unregister(controller);
            Map<UUID, Session> managed = (Map<UUID, Session>) field(controller, "sessions");
            var engine = new EngineState(); engine.settings = animated(WriteDialogueSettings.Mode.CHARACTER, 2, true);
            Conversation interrupted = story(); interrupted.first().interruptActions.add("player_command_as_op: give @s minecraft:copper_ingot 1");
            alice.getInventory().clearContent();
            Session first = session(engine, interrupted, alice); first.tick(); managed.put(alice.getUUID(), first); alice.clear();
            controller.onLogout(new PlayerEvent.PlayerLoggedOutEvent(alice));
            check(first.isFinished() && bar(first) == null && holograms(first).isEmpty() && managed.isEmpty(), "logout_controller_closes_owned_displays");
            alice.pump(); check(alice.actionBars().equals(List.of("")) && field(first, "writer") == null, "logout_clears_actionbar_and_pending_writer");
            check(alice.getInventory().countItem(Items.COPPER_INGOT) == 1, "logout_runs_current_node_interrupt_actions");
            Session reload = session(engine, interrupted, alice); reload.tick(); managed.put(alice.getUUID(), reload); alice.clear();
            controller.onRegisterCommands(new RegisterCommandsEvent(server.getCommands().getDispatcher(), Commands.CommandSelection.DEDICATED,
                    CommandBuildContext.simple(server.registryAccess(), FeatureFlags.DEFAULT_FLAGS)));
            server.getCommands().getDispatcher().execute("interactions reload", server.createCommandSourceStack().withPermission(4));
            check(reload.isFinished() && bar(reload) == null && holograms(reload).isEmpty() && managed.isEmpty(), "reload_controller_closes_owned_displays");
            alice.pump(); check(alice.actionBars().equals(List.of("")) && field(reload, "writer") == null, "reload_clears_actionbar_and_pending_writer");
            check(alice.getInventory().countItem(Items.COPPER_INGOT) == 2, "reload_runs_current_node_interrupt_actions");
            Session stop = session(engine, interrupted, alice); stop.tick(); managed.put(alice.getUUID(), stop); alice.clear();
            controller.onServerStopping(new ServerStoppingEvent(server));
            check(stop.isFinished() && bar(stop) == null && holograms(stop).isEmpty() && managed.isEmpty(), "shutdown_controller_closes_owned_displays");
            alice.pump(); check(alice.actionBars().equals(List.of("")) && field(stop, "writer") == null, "shutdown_clears_actionbar_and_pending_writer");
            check(alice.getInventory().countItem(Items.COPPER_INGOT) == 3, "shutdown_runs_current_node_interrupt_actions");
        }

        void respawn() throws Exception {
            Session session = session(new EngineState(), story(), alice); session.tick();
            Set<Integer> entityIds = ids(holograms(session)); UUID barId = bar(session).getId();
            alice.clear();
            ServerPlayer replacement = server.getPlayerList().respawn(alice, false, Entity.RemovalReason.KILLED);
            replacement.setPos(1, -60, 1);
            session.tick(); alice.pump();
            check(session.player() == replacement && !session.isFinished(), "respawn_rebinds_session_to_current_player");
            check(bar(session).getPlayers().equals(Set.of(replacement)) && bar(session).getId().equals(barId),
                    "respawn_relinks_the_same_private_bossbar");
            check(ids(holograms(session)).equals(entityIds) && alice.spawnIds().containsAll(entityIds),
                    "respawn_resends_existing_private_holograms");
            replacement.setPos(30, -60, 1); session.tick();
            check(session.isFinished() && bar(session) == null && holograms(session).isEmpty(),
                    "rebound_player_range_is_checked_after_respawn");
        }

        Session session(EngineState engine, Conversation story, AuditPlayer player) {
            Session result = new Session(engine, story, story.first(), player, npc); sessions.add(result); return result;
        }

        AuditPlayer player(String name) {
            return player(name, UUID.randomUUID());
        }

        AuditPlayer player(String name, UUID uuid) {
            return player(name, uuid, ConnectionType.NEOFORGE);
        }

        AuditPlayer proxyPlayer(String name, UUID uuid) {
            return player(name, uuid, ConnectionType.OTHER);
        }

        private AuditPlayer player(String name, UUID uuid, ConnectionType connectionType) {
            var player = new AuditPlayer(server, level, name, uuid); players.add(player); player.setPos(1, -60, 1);
            var connection = new Connection(PacketFlow.SERVERBOUND);
            player.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    connection.configurePacketHandler(channel.pipeline());
                    channel.pipeline().addLast("display-audit-capture", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                            if (message instanceof Packet<?> packet) player.capture(packet);
                            super.write(context, message, promise);
                        }
                    });
                }
            });
            if (connectionType == ConnectionType.NEOFORGE) NetworkRegistry.configureMockConnection(connection);
            else {
                net.neoforged.neoforge.network.registration.ChannelAttributes.setPayloadSetup(connection,
                        net.neoforged.neoforge.network.registration.NetworkPayloadSetup.empty());
                net.neoforged.neoforge.network.registration.ChannelAttributes.setConnectionType(connection, connectionType);
            }
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, connectionType);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return player;
        }

        void close() {
            for (Session session : sessions) session.end(false);
            for (AuditPlayer player : players) {
                ServerPlayer current = server.getPlayerList().getPlayer(player.getUUID());
                if (current != null) server.getPlayerList().remove(current);
                if (player.channel != null) player.channel.finishAndReleaseAll();
            }
        }
    }

    private static Conversation story() {
        Conversation story = new Conversation(); story.name = "{centered}&bSpeaker"; story.source = "display.yml";
        story.blockMovement = true; story.hologram = new HologramSettings(true, 2.7, 0);
        var node = new Conversation.Node("conversation1"); var line = new Conversation.Line(); line.time = 2;
        line.text.addAll(List.of("&aHello %player%", "{centered}Line %next%", "", "Tail")); node.lines.add(line);
        var option = new Conversation.Option(); option.text = "Next"; option.startConversation = "conversation2"; node.options.add(option);
        var second = new Conversation.Option(); second.text = "Stop"; node.options.add(second);
        var target = new Conversation.Node("conversation2"); var targetLine = new Conversation.Line(); targetLine.time = -1;
        targetLine.text.add("After choice %next%"); target.lines.add(targetLine);
        story.nodes.put(node.key, node); story.nodes.put(target.key, target); return story;
    }

    private static DialogueSettings settings(BossBarSettings bar) {
        return new DialogueSettings(false, false, false, List.of(), false, true, true, false, SelectionSettings.DEFAULT,
                ConversationStartClick.RIGHT_CLICK, bar);
    }

    private static DialogueSettings animated(WriteDialogueSettings.Mode mode, int delay, boolean actionBar) {
        return new DialogueSettings(false, false, false, List.of(), true, true, true, false, SelectionSettings.DEFAULT,
                ConversationStartClick.RIGHT_CLICK, BossBarSettings.DEFAULT, actionBar, new WriteDialogueSettings(true, mode, delay));
    }

    private static final class EngineState implements Session.Engine {
        DialogueSettings settings = DialogueDisplayRuntimeAudit.settings(BossBarSettings.DEFAULT);
        DialogueMessages messages = new DialogueMessages(null, null, null, null, null, null, null, null,
                "Talk: %name%", "Choose: %name%", "Status: %name%", "Pick: %name%");
        final Actions actions = new Actions(new ItemLibrary(), new Economy());
        final ProgressStore progress = new ProgressStore(new java.io.File("config/display-audit-players"));
        public DialogueSettings settings() { return settings; }
        public DialogueMessages messages() { return messages; }
        public Actions actions() { return actions; }
        public ProgressStore progress() { return progress; }
    }

    private static ServerBossEvent bar(Session session) throws Exception { return (ServerBossEvent) field(field(session, "bossBar"), "bar"); }
    @SuppressWarnings("unchecked")
    private static List<ArmorStand> holograms(Session session) throws Exception {
        List<EntityPacketTracker> trackers = (List<EntityPacketTracker>) field(field(session, "hologram"), "trackers");
        List<ArmorStand> result = new ArrayList<>();
        for (EntityPacketTracker tracker : trackers) result.add((ArmorStand) field(tracker, "entity"));
        return result;
    }
    private static Set<Integer> ids(List<ArmorStand> entities) { return entities.stream().map(Entity::getId).collect(java.util.stream.Collectors.toSet()); }
    private static List<String> choices(AuditPlayer player) {
        player.pump();
        return player.chat.stream().flatMap(c -> c.toFlatList().stream()).map(c -> c.getStyle().getClickEvent())
                .filter(java.util.Objects::nonNull).map(net.minecraft.network.chat.ClickEvent::getValue)
                .filter(command -> command.startsWith("/interactions choose ")).distinct().toList();
    }
    private static Conversation inlineStory() {
        Conversation story = story(); story.first().optionsInDialogue = true;
        story.first().options.getFirst().startConversation = null;
        var line = story.first().lines.getFirst(); line.time = -1; line.text.clear();
        line.text.addAll(List.of("%option_1%", "%option_2%", "%next%")); return story;
    }
    private static Object field(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed++; LoggerFactory.getLogger("interactions").info("[DIALOGUEDISPLAYAUDIT] PASS {}", name); }

    static final class AuditPlayer extends ServerPlayer {
        EmbeddedChannel channel;
        final List<Packet<?>> packets = new ArrayList<>();
        final List<Component> chat = new ArrayList<>();
        final Map<UUID, Component> bars = new HashMap<>();
        AuditPlayer(MinecraftServer server, ServerLevel level, String name, UUID uuid) { super(server, level, new GameProfile(uuid, name), ClientInformation.createDefault()); }
        void capture(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) { bundle.subPackets().forEach(this::capture); return; }
            packets.add(packet);
            if (packet instanceof ClientboundSystemChatPacket system && !system.overlay()) chat.add(system.content());
            if (packet instanceof ClientboundBossEventPacket boss) boss.dispatch(new ClientboundBossEventPacket.Handler() {
                public void add(UUID id, Component name, float progress, BossEvent.BossBarColor color, BossEvent.BossBarOverlay style,
                        boolean darken, boolean music, boolean fog) { bars.put(id, name); }
                public void remove(UUID id) { bars.remove(id); }
                public void updateName(UUID id, Component name) { bars.put(id, name); }
            });
        }
        void pump() { channel.runPendingTasks(); }
        void advanceEffects() { super.tickEffects(); }
        void clear() { pump(); packets.clear(); chat.clear(); }
        List<String> actionBars() { pump(); return packets.stream().filter(ClientboundSetActionBarTextPacket.class::isInstance)
                .map(ClientboundSetActionBarTextPacket.class::cast).map(packet -> packet.text().getString()).toList(); }
        Set<Integer> spawnIds() { pump(); return packets.stream().filter(ClientboundAddEntityPacket.class::isInstance)
                .map(ClientboundAddEntityPacket.class::cast).map(ClientboundAddEntityPacket::getId).collect(java.util.stream.Collectors.toSet()); }
        Set<Integer> removedIds() { pump(); return packets.stream().filter(ClientboundRemoveEntitiesPacket.class::isInstance)
                .map(ClientboundRemoveEntitiesPacket.class::cast).flatMap(packet -> packet.getEntityIds().stream()).collect(java.util.stream.Collectors.toSet()); }
    }
}
