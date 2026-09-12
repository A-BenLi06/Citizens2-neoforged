package net.yuuniverse.interactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

/** Acceptance probes kept separate from the default suite: these expose open parity defects. */
public class LegacyParityAuditTest {
    @Test
    void speakerFormatLoadsAndInvalidReloadRetainsSnapshot() throws Exception {
        Path file = directory.resolve("speaker.yml");
        Files.writeString(file, "nameFormat: '&6[%name%]'\n");
        var messages = DialogueMessages.load(file.toFile(), DialogueMessages.DEFAULT);
        assertEquals("[Guide]", messages.speakerName("Guide").getString());
        Files.writeString(file, "nameFormat: [invalid]\n");
        assertEquals(messages, DialogueMessages.load(file.toFile(), messages));
        Files.writeString(file, "nameFormat: ''\n");
        assertEquals("", DialogueMessages.load(file.toFile(), messages).speakerName("Guide").getString());
    }

    @Test
    void startClickModesDistinguishSneaking() {
        assertTrue(ConversationStartClick.RIGHT_CLICK.permits(false));
        assertFalse(ConversationStartClick.RIGHT_CLICK.permits(true));
        assertFalse(ConversationStartClick.SHIFT_RIGHT_CLICK.permits(false));
        assertTrue(ConversationStartClick.SHIFT_RIGHT_CLICK.permits(true));
        assertTrue(ConversationStartClick.ALL_RIGHT_CLICK.permits(false));
        assertTrue(ConversationStartClick.ALL_RIGHT_CLICK.permits(true));
    }

    @Test
    void invalidClickModeRetainsPreviousSettings() throws Exception {
        Path file = directory.resolve("click.yml");
        Files.writeString(file, "conversation_start_click_type: SHIFT_RIGHT_CLICK\n");
        var settings = DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT);
        assertEquals(ConversationStartClick.SHIFT_RIGHT_CLICK, settings.startClick());
        Files.writeString(file, "conversation_start_click_type: invalid\n");
        assertEquals(settings, DialogueSettings.load(file.toFile(), settings));
    }

    @Test
    void selectionSettingsValidateModeAndOverflow() throws Exception {
        Path file = directory.resolve("selection.yml");
        Files.writeString(file, "selectable_options: true\nselectable_options_mode: SCROLL\nselectable_options_restart_on_overflow: false\n");
        var settings = DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT);
        assertEquals(new SelectionSettings(true, SelectionSettings.Mode.SCROLL, false), settings.selection());
        Files.writeString(file, "selectable_options_mode: invalid\n");
        assertEquals(settings, DialogueSettings.load(file.toFile(), settings));
    }

    @Test
    void selectedOptionMessagesLoadSeparatelyFromNormalOptions() throws Exception {
        Path file = directory.resolve("messages.yml");
        Files.writeString(file, "selectableOptionsFormatNormal: 'Row %number% %text%'\nselectableOptionsFormatSelected: 'Selected %number% %text%'\n");
        var messages = DialogueMessages.load(file.toFile(), DialogueMessages.DEFAULT);
        assertEquals("Row 1 Choice", messages.selectableLabel(1, "&aChoice", false, null).getString());
        assertEquals("Selected 2 Choice", messages.selectableLabel(2, "&aChoice", true, null).getString());
    }

    @Test
    void optionMessageTemplatesLoadAndPreserveUnknownMessages() throws Exception {
        Path file = directory.resolve("options.yml");
        String source = "optionsFormat: '&6%number%. %text%'\nclickableOptionHover: 'Pick %option%'\n"
                + "optionsMainFormat: ['Header', '%options%', 'Footer']\nunknown: retained\n";
        Files.writeString(file, source);
        var messages = DialogueMessages.load(file.toFile(), DialogueMessages.DEFAULT);
        assertEquals("2. Choice", messages.optionLabel(2, "Choice", null).getString());
        assertEquals("Pick 2", messages.optionTooltip(2, null).getString());
        assertEquals(java.util.List.of("Header", "%options%", "Footer"), messages.optionsMainFormat());
        assertEquals(source, Files.readString(file));
    }

    @Test
    void invalidOptionLayoutRetainsPreviousMessages() throws Exception {
        Path file = directory.resolve("options.yml");
        Files.writeString(file, "optionsMainFormat: [17]\n");
        var previous = new DialogueMessages("Next", "Continue", "%text%", "%option%", java.util.List.of("%options%"));
        assertEquals(previous, DialogueMessages.load(file.toFile(), previous));
    }

    @Test
    void optionClickAndSpacingSettingsCanBeDisabled() throws Exception {
        assertTrue(DialogueSettings.DEFAULT.clickableOptions());
        assertTrue(DialogueSettings.DEFAULT.useEmptySpaces());
        Path file = directory.resolve("options.yml");
        Files.writeString(file, "clickable_options: false\nuse_empty_spaces: false\n");
        var settings = DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT);
        assertFalse(settings.clickableOptions());
        assertFalse(settings.useEmptySpaces());
    }

    @Test
    void inventoryRestrictionLoadsWithoutChangingLegacyDefault() throws Exception {
        assertTrue(DialogueSettings.DEFAULT.allowInventoryInteract());
        Path file = directory.resolve("inventory.yml");
        Files.writeString(file, "allow_inventory_interact_while_in_conversation: false\n");
        var restricted = DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT);
        assertFalse(restricted.allowInventoryInteract());
        Files.writeString(file, "allow_inventory_interact_while_in_conversation: true\n");
        assertTrue(DialogueSettings.load(file.toFile(), restricted).allowInventoryInteract());
    }

    @Test
    void lineRoutingAndManualDurationLoadFromLegacyYaml() throws Exception {
        Files.writeString(directory.resolve("route.yml"), """
                starts_with: ['NPC with id 1']
                conversation:
                  conversation1:
                    dialogue:
                      dialogue1:
                        text: ['Wait %next%']
                        time: -1
                        start_conversation: conversation2
                        start_options: conversation3
                        conditional_dialogue:
                          conditional1:
                            text: ['Alternative']
                            start_conversation: conversation4
                  conversation2: {}
                  conversation3: {}
                  conversation4: {}
                """);
        var library = new ConversationLibrary();
        library.load(directory.toFile());
        var line = library.forNpc(1).first().lines.get(0);
        assertEquals(-1, line.time);
        assertEquals("conversation2", line.startConversation);
        assertEquals("conversation3", line.startOptions);
        assertEquals("conversation4", line.conditional.get(0).startConversation);
    }

    @Test
    void nextMarkerControlsCommandSkipping() {
        var line = new Conversation.Line();
        line.text.add("Ordinary text");
        assertFalse(line.canBeSkipped());
        line.text.add("Continue %next%");
        assertTrue(line.canBeSkipped());
        assertTrue(DialogueSettings.DEFAULT.permitsCommand("interactions skipdialogue"));
        assertFalse(DialogueSettings.DEFAULT.permitsCommand("interactions skipdialogueother"));
    }

    @Test
    void npcClickSkippingIsOptIn() throws Exception {
        assertFalse(DialogueSettings.DEFAULT.skipDialogueOnNpcClick());
        Path file = directory.resolve("skip.yml");
        Files.writeString(file, "skip_dialogue_on_npc_click: true\n");
        assertTrue(DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT).skipDialogueOnNpcClick());
    }

    @Test
    void nextMessagesPreserveCustomTextAndRejectInvalidReload() throws Exception {
        Path file = directory.resolve("messages.yml");
        String contents = "nextDialogueText: '&aContinue'\nnextDialogueHover: '&eProceed'\nunknown: preserved\n";
        Files.writeString(file, contents);
        var messages = DialogueMessages.load(file.toFile(), DialogueMessages.DEFAULT);
        assertEquals("Continue", messages.nextLabel().getString());
        assertEquals("Proceed", messages.nextTooltip().getString());
        assertEquals(contents, Files.readString(file));
        Files.writeString(file, "nextDialogueText: [invalid]\n");
        assertEquals(messages, DialogueMessages.load(file.toFile(), messages));
    }

    @Test
    void commandRestrictionsPreserveLegacyPrefixesAndDialogueControls() throws Exception {
        Path file = directory.resolve("commands.yml");
        Files.writeString(file, "allow_commands_while_in_conversation: false\ncommands_whitelist: ['/help', '/login']\n");
        var settings = DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT);
        assertTrue(settings.permitsCommand("HELP topic"));
        assertTrue(settings.permitsCommand("login password"));
        assertTrue(settings.permitsCommand("helper")); // Legacy whitelist uses prefix matching, not command roots.
        assertTrue(settings.permitsCommand("interactions choose 2"));
        assertFalse(settings.permitsCommand("interactions reload"));
        assertFalse(settings.permitsCommand("execute run help"));
        assertFalse(settings.permitsCommand("interactions chooseother"));
        assertTrue(new DialogueSettings(false, false, true, java.util.List.of()).permitsCommand("anything"));
    }

    @Test
    void malformedCommandWhitelistRetainsSettings() throws Exception {
        Path file = directory.resolve("commands.yml");
        Files.writeString(file, "commands_whitelist: [3]\n");
        var previous = new DialogueSettings(true, true, true, java.util.List.of("/help"));
        assertEquals(previous, DialogueSettings.load(file.toFile(), previous));
    }

    @Test
    void globalSettingsDefaultToLegacyRestrictions() {
        assertEquals(DialogueSettings.DEFAULT,
                DialogueSettings.load(directory.resolve("missing.yml").toFile(), new DialogueSettings(true, true)));
    }

    @Test
    void globalSettingsLoadWithoutRewritingUnknownFields() throws Exception {
        Path file = directory.resolve("config.yml");
        String contents = "allow_chat_while_in_conversation: true\nallow_mob_damage: false\nunknown_setting: retained\n";
        Files.writeString(file, contents);
        assertEquals(new DialogueSettings(true, false), DialogueSettings.load(file.toFile(), DialogueSettings.DEFAULT));
        assertEquals(contents, Files.readString(file));
    }

    @Test
    void invalidGlobalSettingsRetainPreviousSnapshot() throws Exception {
        Path file = directory.resolve("config.yml");
        Files.writeString(file, "allow_chat_while_in_conversation: false\nallow_mob_damage: invalid\n");
        DialogueSettings previous = new DialogueSettings(true, true);
        assertEquals(previous, DialogueSettings.load(file.toFile(), previous));
    }

    @Test
    void radiusSettingsMatchLegacyBoundaries() {
        Conversation story = new Conversation();
        assertFalse(story.isWithinStartRadius(0));
        story.startRadius = 3;
        assertTrue(story.isWithinStartRadius(9));
        assertFalse(story.isWithinStartRadius(9.001));
        story.endRadius = 0;
        assertFalse(story.isOutsideEndRadius(1000000));
        story.endRadius = 3;
        assertFalse(story.isOutsideEndRadius(9));
        assertTrue(story.isOutsideEndRadius(9.001));
    }
    @BeforeAll
    static void bootstrap() {
        net.minecraft.server.Bootstrap.bootStrap();
    }
    @TempDir
    Path directory;

    @Test
    void legacyPlingSoundKeepsTheUnderscoreInsideNoteBlock() {
        assertEquals("minecraft:block.note_block.pling", Actions.soundId("BLOCK_NOTE_BLOCK_PLING").toString());
    }

    @Test
    void legacyBellSoundKeepsTheUnderscoreInsideNoteBlock() {
        assertEquals("minecraft:block.note_block.bell", Actions.soundId("BLOCK_NOTE_BLOCK_BELL").toString());
    }

    @Test
    void namespacedSoundIsPreserved() {
        assertEquals("minecraft:block.note_block.pling", Actions.soundId("minecraft:block.note_block.pling").toString());
    }

    @Test
    void bukkitCommandPrefixDoesNotRewriteItemArguments() {
        String args = " @s minecraft:paper[minecraft:custom_name='\"minecraft:give\"'] 1";
        assertEquals("give" + args, LegacyCommand.normalize("/minecraft:give" + args, "give"::equals));
        assertEquals("setblock\t~ ~ ~ minecraft:stone",
                LegacyCommand.normalize("minecraft:setblock\t~ ~ ~ minecraft:stone", "setblock"::equals));
    }

    @Test
    void registeredNamespacedAndModCommandsKeepTheirIdentity() {
        assertEquals("minecraft:give @s stone", LegacyCommand.normalize("minecraft:give @s stone", name -> true));
        assertEquals("other:give @s stone", LegacyCommand.normalize("other:give @s stone", "give"::equals));
        assertEquals("minecraft:missing", LegacyCommand.normalize("minecraft:missing", name -> false));
    }

    @Test
    void missingRewardIsDiscoveredBeforeAnyPaymentRuns() {
        var executed = new java.util.ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> ActionBatch.run(java.util.List.of("payment", "missingReward"),
                action -> {
                    if (action.equals("missingReward")) throw new IllegalArgumentException("Missing item");
                    return () -> executed.add(action);
                }));
        assertTrue(executed.isEmpty());
    }

    @Test
    void failedPaymentStopsRewardsAndLaterActions() {
        var executed = new java.util.ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> ActionBatch.run(java.util.List.of("payment", "reward"),
                action -> () -> {
                    if (action.equals("payment")) throw new IllegalStateException("Debit rejected");
                    executed.add(action);
                }));
        assertTrue(executed.isEmpty());
    }

    @Test
    void overlappingItemCostsAreReservedWithoutChangingTheRealStack() {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 5);
        var plan = new CheckItem.PaymentPlan(java.util.List.of(stack));
        plan.reserve("%checkitem_remove_mat:minecraft:paper,amt:3%");
        assertThrows(IllegalStateException.class, () -> plan.reserve("%checkitem_matcontains:paper,amt:3%"));
        assertEquals(5, stack.getCount());
    }

    @Test
    void invalidPaymentAmountsDoNotSilentlyBecomeOne() {
        var plan = new CheckItem.PaymentPlan(java.util.List.of(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 5)));
        for (String amount : java.util.List.of("0", "-1", "invalid"))
            assertThrows(IllegalArgumentException.class,
                    () -> plan.reserve("%checkitem_mat:minecraft:paper,amt:" + amount + "%"));
    }

    @Test
    void economyAmountsRespectCurrencyPrecisionWithoutRoundingOrOverflow() {
        assertEquals(1234, Economy.minorUnits("12.34", 2));
        assertEquals(12, Economy.minorUnits("12", 0));
        assertThrows(ArithmeticException.class, () -> Economy.minorUnits("1.001", 2));
        assertThrows(ArithmeticException.class, () -> Economy.minorUnits("999999999999999999999999", 2));
        assertThrows(IllegalArgumentException.class, () -> Economy.minorUnits("-1", 2));
    }

    @Test
    void progressSaveDoesNotEraseExistingCooldownRecords() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".yml");
        Files.writeString(file, "name: AuditPlayer\nsaved_dialogues: []\ncooldowns:\n- story;1900000000000\n");
        ProgressStore store = new ProgressStore(directory.toFile());
        assertEquals(1, store.loadAll());
        store.markSeen(player, "AuditPlayer", "story.conversation1.dialogue1");
        store.saveDirty();
        assertTrue(Files.readString(file).contains("story;1900000000000"),
                "Saving dialogue progress must preserve the existing cooldown record");
    }

    @Test
    void cooldownsUseLegacyStartTimesAndRemainIndependentAfterRestart() {
        UUID player = UUID.randomUUID();
        ProgressStore store = new ProgressStore(directory.toFile());
        store.setCooldown(player, "Player", "bank", 100000);
        store.setCooldown(player, "Player", "station", 110000);
        store.saveDirty();
        ProgressStore restart = new ProgressStore(directory.toFile());
        assertEquals(1, restart.loadAll());
        assertTrue(restart.isCoolingDown(player, "bank", 30, 120000));
        assertFalse(restart.isCoolingDown(player, "bank", 30, 130000));
        assertFalse(restart.isCoolingDown(player, "unrelated", 3600, 120000));
        assertTrue(restart.isCoolingDown(player, "station", 30, 130000));
    }

    @Test
    void yamlSensitiveDialogueKeysAndUnknownFieldsSurvive() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".yml");
        Files.writeString(file, "influence: [faction;25]\nsaved_dialogues: []\ncooldowns: []\n");
        ProgressStore store = new ProgressStore(directory.toFile());
        store.loadAll();
        String key = "story: #one.conversation1.dialogue1";
        store.markSeen(player, "Player", key);
        store.saveDirty();
        store.loadAll();
        assertTrue(store.hasSeen(player, key));
        assertTrue(Files.readString(file).contains("faction;25"));
    }

    @Test
    void corruptRecordCannotBeReplacedWithEmptyProgress() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = directory.resolve(player + ".yml");
        String invalid = "saved_dialogues: [unterminated";
        Files.writeString(file, invalid);
        ProgressStore store = new ProgressStore(directory.toFile());
        assertEquals(0, store.loadAll());
        assertFalse(store.isReadable(player));
        assertThrows(IllegalStateException.class, () -> store.markSeen(player, "Player", "new"));
        store.saveDirty();
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void randomDialogueCanChooseEveryLineWithoutChangingStoredOrder() {
        Conversation.Node node = new Conversation.Node("conversation1");
        node.randomDialogue = true;
        for (int i = 0; i < 3; i++) {
            Conversation.Line line = new Conversation.Line();
            line.key = "dialogue" + i;
            node.lines.add(line);
        }
        var picked = new java.util.HashSet<String>();
        var random = net.minecraft.util.RandomSource.create(71235);
        for (int i = 0; i < 100; i++)
            picked.add(node.orderedLines(random).getFirst().key);
        assertEquals(3, picked.size());
        assertEquals("dialogue0", node.lines.getFirst().key);
    }
}
