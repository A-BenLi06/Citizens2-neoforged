package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.trait.Owner;
import net.citizensnpcs.api.trait.trait.Spawned;
import net.citizensnpcs.api.util.ChatPrompt;
import net.citizensnpcs.api.util.ChatPromptSession;
import net.citizensnpcs.api.util.ChatPrompts;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.text.Text;
import net.citizensnpcs.trait.text.TextEditor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class TextEditorRuntimeAudit {
    private static State state;
    private static boolean forced, finished;
    private static int passed;

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (finished) return;
        MinecraftServer server = event.getServer(); ServerLevel level = server.overworld();
        if (!forced) {
            for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) level.setChunkForced(x, z, true);
            forced = true;
        }
        try {
            for (int x = 0; x <= 2; x++) for (int z = 0; z <= 2; z++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(x, z)) || !level.isPositionEntityTicking(new BlockPos(x * 16, -60, z * 16))) {
                    if (server.getTickCount() > 1500) throw new AssertionError("Fixture chunks did not load");
                    return;
                }
            }
            if (state == null) state = new State(server);
            if (state.next()) return;
            LoggerFactory.getLogger("citizens").info("[TEXTEDITORAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[TEXTEDITORAUDIT] FAILED phase " + (state == null ? "setup" : state.phase), failure);
        }
        finished = true;
        try { if (state != null) state.close(); }
        catch (Throwable failure) { LoggerFactory.getLogger("citizens").error("[TEXTEDITORAUDIT] FAILED cleanup", failure); }
        server.halt(false);
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    private record Step(String name, Action action) { }

    private static final class State {
        final MinecraftServer server;
        final ServerLevel level;
        final List<Step> steps = new ArrayList<>();
        final List<PermissionUtil.Attachment> grants = new ArrayList<>();
        final List<Actor> actors = new ArrayList<>();
        final List<NPC> created = new ArrayList<>();
        Actor alice, bob;
        NPC npc, other, removed;
        Text text, detached;
        PermissionUtil.Attachment editorGrant;
        SpyPrompt spy;
        ChatPromptSession replacement;
        List<String> before;
        int cursor;
        String phase;
        boolean isolated;

        State(MinecraftServer server) {
            this.server = server; level = server.overworld();
            if (!Files.exists(Path.of("text-editor-audit-fixture.txt")) || CitizensAPI.getNPCRegistry().iterator().hasNext())
                throw new AssertionError("Text editor audit requires an empty isolated fixture");
            isolated = true;
            alice = player("TextAlice", 0.5, 1.5); bob = player("TextBob", 25, 25);
            npc = npc("TextTarget", alice.current().getUUID()); other = npc("OtherTarget", bob.current().getUUID());
            script();
        }

        boolean next() throws Exception {
            if (cursor == steps.size()) return false;
            Step step = steps.get(cursor++); phase = step.name; step.action.run(); return true;
        }
        void step(String name, Action action) { steps.add(new Step(name, action)); }

        void script() {
            step("permissions_and_open", () -> {
                select(npc); bad("npc text"); check(!npc.hasTrait(Text.class), "denied_open_does_not_attach_text");
                check(PermissionAPI.getRegisteredNodes().stream().anyMatch(n -> n.getNodeName().equals(TextEditor.PERMISSION)), "text_permission_node_registered");
                try { server.getCommands().getDispatcher().execute("npc text", server.createCommandSourceStack().withPermission(4)); throw new AssertionError("Console opened text editor"); }
                catch (CommandSyntaxException expected) { check(true, "text_editor_requires_player"); }
                grantEditor(); ok("npc text"); text = npc.getOrAddTrait(Text.class);
                check(Editor.getEditor(alice.current()) instanceof TextEditor && ChatPrompts.isActive(alice.current()), "opens_editor_and_prompt_for_unspawned_npc");
                check(text.getTexts().isEmpty(), "opening_unspawned_editor_does_not_insert_default_text");
                check(alice.messages().stream().anyMatch(c -> c.getString().contains(npc.getName())), "editor_header_identifies_bound_npc");
                chat("add <red>Alpha  §bBeta --literal flag  ");
            });
            step("chat_and_commands", () -> {
                check(text.getTexts().equals(List.of("<red>Alpha  §bBeta --literal flag  ")), "chat_input_preserves_markup_spaces_and_flag_like_text");
                check(bob.messages().stream().noneMatch(c -> c.getString().contains("Alpha")), "editor_input_and_prompts_are_private");
                ok("npc text add \"Quoted --id 999 marker\"");
                check(text.getTexts().get(1).equals("Quoted --id 999 marker"), "quoted_command_body_is_literal");
                ok("npc text edit 1 \"Changed <blue>line\"");
                check(text.getTexts().get(1).equals("Changed <blue>line"), "command_edit_updates_requested_index");
                before = List.copyOf(text.getTexts());
                bad("npc text add truncated --bogus value");
                bad("npc text --id " + other.getId() + " add wrong target");
                check(text.getTexts().equals(before) && !other.hasTrait(Text.class), "unknown_flags_and_conflicting_explicit_targets_do_not_mutate");
                ok("npc text --id " + npc.getId() + " edit 1 MatchedTarget");
                check(text.getTexts().get(1).equals("MatchedTarget"), "matching_explicit_target_is_accepted");
                invalidInputs(); settings();
            });
            step("pagination_copy_and_binding", () -> {
                NPC copy = npc.copy();
                check(snapshot(copy).getRaw("traits.text").equals(snapshot(npc).getRaw("traits.text")), "text_settings_and_lines_survive_copy"); copy.destroy();
                for (int i = 0; i < 10; i++) ok("npc text add Row" + i);
                alice.clear(); ok("npc text page 2");
                check(alice.clicks().stream().anyMatch(c -> c.getAction() == ClickEvent.Action.RUN_COMMAND && c.getValue().equals("/npc text page 1")), "text_pagination_button_routes_to_command");
                check(alice.clicks().stream().anyMatch(c -> c.getAction() == ClickEvent.Action.SUGGEST_COMMAND && c.getValue().trim().equals("edit 9")), "text_edit_button_retains_entry_index");
                for (int i = text.getTexts().size() - 1; i >= 0; i--) ok("npc text remove " + i);
                check(text.getTexts().isEmpty() && ChatPrompts.isActive(alice.current()), "deleting_last_page_keeps_valid_editor");
                ok("npc text add First"); ok("npc text add Second");
                select(other); ok("npc text edit 0 BoundTarget");
                check(text.getTexts().getFirst().equals("BoundTarget") && !other.hasTrait(Text.class), "selection_change_does_not_redirect_active_editor");
                ok("npc text"); check(!Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "repeating_text_command_closes_editor");
                bad("npc text"); check(!other.hasTrait(Text.class), "ownership_checked_before_open");
                select(npc); ok("npc text"); before = List.copyOf(text.getTexts()); chat("add stale queued input");
                spy = new SpyPrompt(); replacement = ChatPrompts.begin(alice.current(), spy, null, s -> s.onAbandon(() -> spy.abandoned++));
            });
            step("prompt_replacement", () -> {
                check(text.getTexts().equals(before) && spy.inputs == 0, "queued_input_does_not_reach_replacement_prompt");
                check(!Editor.hasEditor(alice.current()) && ChatPrompts.isActive(replacement), "replacement_abandons_editor_but_keeps_new_prompt");
                bad("npc text add blocked"); check(spy.inputs == 0, "text_command_does_not_feed_unrelated_prompt");
                ChatPrompts.abandon(replacement); ChatPrompts.abandon(replacement);
                check(spy.abandoned == 1, "prompt_abandonment_callback_runs_once");
                ok("npc text"); editorGrant.remove(); before = List.copyOf(text.getTexts()); chat("add denied");
            });
            step("permission_revocation", () -> {
                check(text.getTexts().equals(before) && !Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "revoked_permission_rejects_chat_mutation_and_closes_editor");
                grantEditor(); ok("npc text"); npc.getOrAddTrait(Owner.class).setOwner(bob.current().getUUID());
                chat("add denied owner");
            });
            step("ownership_revocation", () -> {
                check(text.getTexts().equals(before) && !Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "changed_owner_rejects_chat_mutation_and_closes_editor");
                npc.getOrAddTrait(Owner.class).setOwner(alice.current().getUUID()); ok("npc text");
                detached = text; chat("add stale trait"); npc.removeTrait(Text.class);
                check(!npc.hasTrait(Text.class) && npc.getTraitNullable(Text.class) == null, "removed_trait_is_absent_from_both_lookup_views");
                check(!Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "trait_removal_closes_editor_immediately");
                text = npc.getOrAddTrait(Text.class);
                check(text != detached, "reattaching_text_creates_a_new_trait");
            });
            step("trait_and_npc_removal", () -> {
                check(detached.getTexts().equals(before) && text.getTexts().isEmpty(), "queued_input_does_not_edit_removed_or_replacement_trait");
                removed = npc("RemovedTarget", alice.current().getUUID()); select(removed); ok("npc text");
                detached = removed.getOrAddTrait(Text.class); chat("add after destruction"); removed.destroy();
                check(!Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "npc_removal_closes_editor_immediately");
            });
            step("respawn", () -> {
                check(detached.getTexts().isEmpty(), "queued_input_does_not_edit_destroyed_npc");
                select(npc); ok("npc text add BeforeRespawn");
                check(text.getTexts().equals(List.of("BeforeRespawn")), "initial_command_with_input_opens_and_edits");
                ServerPlayer old = alice.current();
                ServerPlayer newPlayer = server.getPlayerList().respawn(old, false, Entity.RemovalReason.KILLED);
                newPlayer.setPos(0.5, -60, 1.5);
                check(newPlayer != old && Editor.hasEditor(newPlayer) && ChatPrompts.isActive(newPlayer), "respawn_keeps_editor_session");
                chat("edit 0 AfterRespawn");
            });
            step("respawn_input_and_exit", () -> {
                check(text.getTexts().equals(List.of("AfterRespawn")), "chat_after_respawn_uses_replacement_player");
                ok("npc text add CommandAfterRespawn");
                check(text.getTexts().getLast().equals("CommandAfterRespawn"), "command_buttons_work_after_respawn_without_reselection");
                chat("exit");
            });
            step("escape_and_other_editor", () -> {
                check(!Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "chat_exit_releases_editor_and_prompt");
                SpyEditor editor = new SpyEditor(); Editor.enterOrLeave(alice.current(), editor); select(other); bad("npc text");
                check(Editor.getEditor(alice.current()) == editor && !other.hasTrait(Text.class), "other_editor_is_not_replaced_or_mutated");
                Editor.leave(alice.current()); select(npc);
                grants.add(PermissionUtil.grantTemporary(alice.current(), List.of("citizens.npc.edit.path")));
                var routeEditor = new net.citizensnpcs.trait.waypoint.WaypointEditor() {
                    public void begin() { }
                    public void end() { }
                };
                Editor.enterOrLeave(alice.current(), routeEditor);
                SpyPrompt routePrompt = new SpyPrompt(); ChatPromptSession routeSession = ChatPrompts.begin(alice.current(), routePrompt);
                ok("npc path add delay");
                check(routePrompt.inputs == 1 && routePrompt.lastInput.equals("add delay"), "path_command_forwards_to_active_waypoint_prompt");
                check(Editor.getEditor(alice.current()) == routeEditor && ChatPrompts.isActive(routeSession), "path_input_keeps_owning_editor_session");
                ChatPrompts.abandon(routeSession); Editor.leave(alice.current());
                ok("npc text"); chat("/npc text");
            });
            step("literal_escape_and_speech", () -> {
                check(!Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "literal_command_escape_closes_editor");
                speech();
            });
            for (int i = 0; i < 6; i++) step("speech_bubble_expiry_" + i, () -> { });
            step("bubble_expiry_and_visibility", () -> {
                check(npc.getOrAddTrait(HologramTrait.class).getLines().isEmpty(), "speech_bubble_expires_after_configured_ticks");
                visibility();
            });
            step("save_reload_and_logout", () -> reloadAndLogout());
            step("cleanup_lifecycle", () -> lifecycle());
        }

        void invalidInputs() throws Exception {
            List<String> original = List.copyOf(text.getTexts()); int delay = text.getDelay(); double range = text.getRange();
            int duration = text.getSpeechBubbleDuration();
            for (String input : List.of("edit missing changed", "edit 999 changed", "remove", "remove 0 extra", "page 0", "page 1000",
                    "delay NaN", "delay -2", "range NaN", "range Infinity", "speech bubbles duration", "speech bubbles duration -1t",
                    "speech bubbles duration 999999999999d", "item", "unknown")) bad("npc text " + input);
            check(text.getTexts().equals(original) && text.getDelay() == delay && text.getRange() == range
                    && text.getSpeechBubbleDuration() == duration, "invalid_editor_input_preserves_text_and_settings");
            check(Editor.hasEditor(alice.current()) && ChatPrompts.isActive(alice.current()), "invalid_input_keeps_editor_usable");
        }

        void settings() throws Exception {
            boolean close = text.shouldTalkClose(), random = text.isRandomTalker(), bubbles = text.useSpeechBubbles();
            boolean realistic = text.useRealisticLooking(), chat = text.sendTextToChat();
            ok("npc text close"); ok("npc text random"); ok("npc text speech bubbles"); ok("npc text realistic looking"); ok("npc text send text to chat");
            check(text.shouldTalkClose() != close && text.isRandomTalker() != random && text.useSpeechBubbles() != bubbles
                    && text.useRealisticLooking() != realistic && text.sendTextToChat() != chat, "all_text_mode_toggles_apply");
            ok("npc text delay 5"); ok("npc text range -2"); check(text.getRange() == 0, "negative_text_range_clamps_to_zero");
            ok("npc text range 6"); ok("npc text item \"minecraft:stick, minecraft:stone\""); ok("npc text Speech Bubbles Duration 4t");
            check(text.getDelay() == 5 && text.getRange() == 6 && text.getItemInHandPattern().equals("minecraft:stick, minecraft:stone")
                    && text.getSpeechBubbleDuration() == 4, "delay_range_item_pattern_and_duration_apply");
        }

        void speech() throws Exception {
            select(npc); ok("npc text");
            while (!text.getTexts().isEmpty()) ok("npc text remove " + (text.getTexts().size() - 1));
            ok("npc text add First"); ok("npc text add Second"); ok("npc text delay 0"); ok("npc text range 6");
            if (text.shouldTalkClose()) ok("npc text close");
            if (text.isRandomTalker()) ok("npc text random");
            if (text.useSpeechBubbles()) ok("npc text speech bubbles");
            if (text.useRealisticLooking()) ok("npc text realistic looking");
            if (!text.sendTextToChat()) ok("npc text send text to chat");
            ok("npc text item minecraft:stick");
            check(npc.spawn(new Location(level, 3.5, -60, 1.5)), "edited_npc_spawns");
            alice.current().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND));
            alice.clear(); NPCRightClickEvent wrongItem = click(npc);
            check(!wrongItem.isDelayedCancellation() && spoken().isEmpty(), "talk_item_filter_blocks_wrong_hand_item");
            ok("npc text item missingprovider:unknown_item");
            alice.current().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            alice.clear(); NPCRightClickEvent missingItem = click(npc);
            check(!missingItem.isDelayedCancellation() && spoken().isEmpty(), "unknown_talk_item_does_not_match_empty_hand");
            ok("npc text item minecraft:air"); alice.clear(); click(npc);
            check(spoken().size() == 1, "explicit_air_talk_item_matches_empty_hand");
            ok("npc text item minecraft:stick");
            alice.current().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
            alice.clear(); click(npc); click(npc); click(npc);
            List<String> sequence = spoken();
            check(sequence.size() == 3 && sequence.get(0).contains("Second") && sequence.get(1).contains("First")
                    && sequence.get(2).contains("Second"), "edited_text_plays_in_order_and_wraps");
            ok("npc text random"); alice.clear();
            for (int i = 0; i < 8; i++) click(npc);
            check(spoken().size() == 8 && spoken().stream().allMatch(line -> line.contains("First") || line.contains("Second")),
                    "random_talker_selects_only_authored_lines");
            ok("npc text random"); ok("npc text speech bubbles"); ok("npc text send text to chat"); ok("npc text speech bubbles duration 4t");
            alice.clear(); click(npc);
            check(spoken().isEmpty() && npc.getOrAddTrait(HologramTrait.class).getLines().size() == 1,
                    "speech_bubble_mode_uses_temporary_hologram_without_chat");
            MemoryDataKey data = snapshot(npc);
            Object savedLines = data.getRaw("traits.hologram.lines");
            check(savedLines == null || savedLines instanceof Map<?, ?> map && map.isEmpty(), "temporary_speech_bubble_is_not_persisted");
        }

        void visibility() throws Exception {
            ok("npc text speech bubbles"); ok("npc text send text to chat");
            if (!text.shouldTalkClose()) ok("npc text talk close");
            if (!text.useRealisticLooking()) ok("npc text realistic looking");
            BlockPos low = new BlockPos(2, -60, 1), high = new BlockPos(2, -59, 1);
            var oldLow = level.getBlockState(low); var oldHigh = level.getBlockState(high);
            level.setBlockAndUpdate(low, Blocks.STONE.defaultBlockState()); level.setBlockAndUpdate(high, Blocks.STONE.defaultBlockState());
            try {
                check(!alice.current().hasLineOfSight(npc.getEntity()), "fixture_wall_blocks_native_line_of_sight");
                alice.clear(); text.run(); check(spoken().isEmpty(), "realistic_looking_blocks_speech_through_wall");
                ok("npc text realistic looking"); alice.clear(); text.run();
                check(spoken().size() == 1, "disabling_realistic_looking_allows_occluded_speech");
            } finally { level.setBlockAndUpdate(low, oldLow); level.setBlockAndUpdate(high, oldHigh); }
            alice.current().setPos(8.5, -60, 6.5); alice.clear(); text.run();
            check(spoken().isEmpty(), "talk_close_range_excludes_outside_diagonal");
            alice.current().setPos(9.5, -60, 1.5); alice.clear(); text.run();
            check(spoken().size() == 1, "talk_close_includes_exact_radius_boundary");
            alice.current().setPos(9.51, -60, 1.5); alice.clear(); text.run();
            check(spoken().isEmpty(), "talk_close_excludes_outside_radius_boundary");
            alice.current().setPos(0.5, -60, 1.5); ok("npc text close"); ok("npc text item default"); ok("npc text delay 100");
            alice.current().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            alice.clear(); click(npc); click(npc);
            check(spoken().size() == 1, "positive_text_delay_blocks_immediate_repeat");
            ok("npc text delay -1");
            check(text.getDelay() == -1 && text.getItemInHandPattern().equals("default"), "global_delay_and_item_defaults_can_be_restored");
            NPC removeProbe = npc("RemovalProbe", alice.current().getUUID());
            Text removeText = removeProbe.getOrAddTrait(Text.class); removeText.add("RemovalRunProbe"); removeText.setDelay(0); removeText.setRange(6);
            if (!removeText.shouldTalkClose()) removeText.toggleTalkClose();
            check(removeProbe.spawn(new Location(level, 4.5, -60, 1.5)), "live_trait_removal_probe_spawns");
            alice.clear(); removeProbe.removeTrait(Text.class);
            check(alice.messages().stream().noneMatch(c -> c.getString().contains("RemovalRunProbe")), "removing_trait_does_not_execute_run_again");
            removeProbe.destroy();
        }

        void reloadAndLogout() throws Exception {
            UUID id = npc.getUniqueId(); List<String> expected = List.copyOf(text.getTexts());
            CommandSourceStack console = server.createCommandSourceStack().withPermission(4);
            server.getCommands().getDispatcher().execute("citizens save", console);
            server.getCommands().getDispatcher().execute("citizens reload", console);
            if (Setting.WARN_ON_RELOAD.asBoolean()) server.getCommands().getDispatcher().execute("citizens reload", console);
            check(!Editor.hasEditor(alice.current()) && !ChatPrompts.isActive(alice.current()), "citizens_reload_cleans_editor_and_prompt");
            npc = CitizensAPI.getNPCRegistry().getByUniqueId(id); text = npc.getOrAddTrait(Text.class);
            check(text.getTexts().equals(expected) && text.getDelay() == -1 && text.getRange() == 6
                    && text.getSpeechBubbleDuration() == 4, "saved_editor_configuration_survives_actual_reload");
            select(npc); ok("npc text");
            spy = new SpyPrompt(); ChatPrompts.begin(bob.current(), spy, null, s -> s.onAbandon(() -> spy.abandoned++));
            SpyEditor editor = new SpyEditor(); Editor.enterOrLeave(bob.current(), editor);
            server.getPlayerList().remove(bob.current());
            check(spy.abandoned == 1 && editor.ended == 1 && !ChatPrompts.isActive(bob.initial) && !Editor.hasEditor(bob.initial),
                    "logout_runs_prompt_and_editor_cleanup_once");
        }

        void lifecycle() throws Exception {
            server.getPlayerList().remove(alice.current());
            check(!Editor.hasEditor(alice.initial) && !ChatPrompts.isActive(alice.initial), "text_editor_logout_releases_both_registries");
            Actor finalActor = player("TextFinal", 0.5, 1.5);
            SpyEditor editor = new SpyEditor(); Editor.enterOrLeave(finalActor.current(), editor);
            SpyPrompt prompt = new SpyPrompt(); ChatPrompts.begin(finalActor.current(), prompt, null, s -> s.onAbandon(() -> prompt.abandoned++));
            Editor.leaveAll(); ChatPrompts.abandonAll();
            check(editor.ended == 1 && prompt.abandoned == 1 && !Editor.hasEditor(finalActor.current()) && !ChatPrompts.isActive(finalActor.current()),
                    "global_cleanup_releases_editors_and_prompt_callbacks");
            SpyEditor failed = new SpyEditor() {
                @Override public void begin() { throw new IllegalStateException("expected editor begin failure"); }
            };
            try { Editor.enterOrLeave(finalActor.current(), failed); throw new AssertionError("Editor failure was hidden"); }
            catch (IllegalStateException expected) { }
            check(!Editor.hasEditor(finalActor.current()) && failed.ended == 1, "failed_editor_begin_cleans_registered_state");
            int[] callbacks = {0};
            try {
                ChatPrompts.begin(finalActor.current(), new ChatPrompt() {
                    public String getPromptText(ChatPromptSession session) { throw new IllegalStateException("expected prompt rendering failure"); }
                    public ChatPrompt acceptInput(ChatPromptSession session, String input) { return null; }
                }, null, s -> s.onAbandon(() -> callbacks[0]++));
                throw new AssertionError("Prompt failure was hidden");
            } catch (IllegalStateException expected) { }
            check(!ChatPrompts.isActive(finalActor.current()) && callbacks[0] == 1, "failed_first_prompt_cleans_session_and_callback");
        }

        NPCRightClickEvent click(NPC target) {
            var event = new NPCRightClickEvent(target, alice.current()); NeoForge.EVENT_BUS.post(event); return event;
        }
        List<String> spoken() {
            return alice.messages().stream().map(Component::getString).filter(line -> line.contains("First") || line.contains("Second")).toList();
        }

        NPC npc(String name, UUID owner) {
            NPC result = CitizensAPI.getNPCRegistry().createNPC(EntityType.PIG, name); created.add(result);
            result.getOrAddTrait(Owner.class).setOwner(owner); result.getOrAddTrait(Spawned.class).setSpawned(false); return result;
        }
        void select(NPC target) { CitizensAPI.getDefaultNPCSelector().select(source(), target); }
        CommandSourceStack source() { return alice.current().createCommandSourceStack().withPermission(0); }
        void grantEditor() { editorGrant = PermissionUtil.grantTemporary(alice.current(), List.of(TextEditor.PERMISSION, "citizens.npc.select")); grants.add(editorGrant); }
        void ok(String command) throws Exception {
            try { if (server.getCommands().getDispatcher().execute(command, source()) <= 0) throw new AssertionError("Command failed: " + command); }
            catch (CommandSyntaxException failure) { throw new AssertionError("Command failed: " + command, failure); }
        }
        void bad(String command) throws Exception {
            try { server.getCommands().getDispatcher().execute(command, source()); }
            catch (CommandSyntaxException expected) { return; }
            throw new AssertionError("Invalid command succeeded: " + command);
        }
        void chat(String message) {
            ServerChatEvent event = new ServerChatEvent(alice.current(), message, Component.literal(message));
            NeoForge.EVENT_BUS.post(event); check(event.isCanceled(), "private_chat_input_captured_" + cursor);
        }
        Actor player(String name, double x, double z) {
            ServerPlayer player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), name), ClientInformation.createDefault());
            player.setPos(x, -60, z); Actor actor = new Actor(server, player); actors.add(actor);
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            actor.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel channel) {
                    connection.configurePacketHandler(channel.pipeline());
                    channel.pipeline().addLast("text-editor-audit", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
                            actor.capture(message); super.write(context, message, promise);
                        }
                    });
                }
            });
            NetworkRegistry.configureMockConnection(connection);
            var cookie = new CommonListenerCookie(player.getGameProfile(), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess(), cookie.connectionType())));
            server.getPlayerList().placeNewPlayer(connection, player, cookie); return actor;
        }
        void close() {
            Editor.leaveAll(); ChatPrompts.abandonAll(); grants.forEach(PermissionUtil.Attachment::remove);
            if (isolated) CitizensAPI.getNPCRegistry().deregisterAll();
            for (Actor actor : actors) {
                ServerPlayer current = server.getPlayerList().getPlayer(actor.uuid);
                if (current != null) server.getPlayerList().remove(current);
                actor.channel.finishAndReleaseAll();
            }
        }
    }

    private static final class Actor {
        final MinecraftServer server;
        final ServerPlayer initial;
        final UUID uuid;
        EmbeddedChannel channel;
        final List<Component> chat = new ArrayList<>();
        Actor(MinecraftServer server, ServerPlayer initial) { this.server = server; this.initial = initial; uuid = initial.getUUID(); }
        ServerPlayer current() { ServerPlayer current = server.getPlayerList().getPlayer(uuid); return current == null ? initial : current; }
        void capture(Object packet) {
            if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(this::capture);
            else if (packet instanceof ClientboundSystemChatPacket message) chat.add(message.content());
        }
        void clear() { channel.runPendingTasks(); chat.clear(); }
        List<Component> messages() { channel.runPendingTasks(); return List.copyOf(chat); }
        List<ClickEvent> clicks() {
            List<ClickEvent> result = new ArrayList<>();
            for (Component component : messages()) component.visit((style, text) -> {
                if (style.getClickEvent() != null) result.add(style.getClickEvent());
                return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);
            return result;
        }
    }
    private static final class SpyPrompt implements ChatPrompt {
        int inputs, abandoned;
        String lastInput;
        public String getPromptText(ChatPromptSession session) { return ""; }
        public ChatPrompt acceptInput(ChatPromptSession session, String input) { inputs++; lastInput = input; return this; }
    }
    private static class SpyEditor extends Editor {
        int ended;
        public void begin() { }
        public void end() { ended++; }
    }
    private static MemoryDataKey snapshot(NPC npc) { var data = new MemoryDataKey(); npc.saveSnapshot(data); return data; }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        passed++; LoggerFactory.getLogger("citizens").info("[TEXTEDITORAUDIT] PASS {}", name);
    }
}
