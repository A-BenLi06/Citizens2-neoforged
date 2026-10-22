package net.citizensnpcs.audit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbquests.quest.*;
import dev.ftb.mods.ftbquests.quest.task.CustomTask;
import dev.ftb.mods.ftbquests.quest.reward.CustomReward;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.data.PartyTeam;
import io.netty.channel.embedded.EmbeddedChannel;
import net.citizensnpcs.util.FtbMonetaryRewards;
import net.citizensnpcs.util.QuestRewardJournal.Claim;
import net.citizensnpcs.util.QuestRewardJournal.State;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.yuuniverse.economy.api.EconomyApi;
import net.yuuniverse.economy.api.YuuniverseEconomyApi;

/** Real two-member FTB claims and Economy receipts across native session/team changes. */
public final class PartyRewardRuntimeAudit {
    private static int passed;
    private record RewardFixture(CustomTask task, CustomReward reward) { }
    private PartyRewardRuntimeAudit() { }

    public static void run(ServerPlayer owner, ServerQuestFile file, EconomyApi economy) throws Exception {
        var server = owner.getServer();
        var source = owner.createCommandSourceStack().withPermission(2);
        var service = FtbMonetaryRewards.current(server);
        var party = (PartyTeam) FTBTeamsAPI.api().getManager().getTeamForPlayer(owner).orElseThrow();
        var team = file.getTeamData(owner).orElseThrow();
        var chapter = new Chapter(file.newID(), file, file.getDefaultChapterGroup());
        chapter.onCreated();
        var profile = new GameProfile(UUID.randomUUID(), "RewardMember");
        ServerPlayer member = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
        member.setPos(1, -60, 1);
        EmbeddedChannel connection = XModRuntimeAudit.connect(member);
        var currency = economy.defaultCurrency();
        var amount = BigDecimal.valueOf(375, currency.scale());
        long ownerBefore = economy.balance(owner.getUUID(), currency.id());
        economy.ensurePlayerAccount(member.getUUID(), profile.getName());
        long memberBefore = economy.balance(member.getUUID(), currency.id());
        try {
            party.invite(owner, List.of(profile));
            party.join(member);
            check(file.getTeamData(member).orElseThrow() == team && party.getMembers().contains(member.getUUID()),
                    "native_invite_and_join_share_authoritative_two_member_task_state");

            var shared = reward(file, chapter, true, false);
            var sharedBinding = service.bind(source, shared.reward().getCodeString(), currency.id(), amount);
            team.setProgress(shared.task(), shared.task().getMaxProgress());
            YuuniverseEconomyApi.clear(economy);
            try { team.claimReward(member, shared.reward(), false); }
            finally { YuuniverseEconomyApi.publish(economy); }
            var pending = claims(service, source, shared).getFirst();
            check(pending.state() == State.ACCEPTED && pending.intent().beneficiaryId().equals(member.getUUID())
                    && pending.intent().ownerId().equals(team.getTeamId()) && pending.intent().binding().equals(sharedBinding)
                    && economy.balance(member.getUUID(), currency.id()) == memberBefore,
                    "shared_native_claim_freezes_first_member_and_team_before_pending_payment");
            team.claimReward(owner, shared.reward(), false);
            check(claims(service, source, shared).size() == 1 && economy.balance(owner.getUUID(), currency.id()) == ownerBefore,
                    "second_party_member_cannot_redirect_or_duplicate_shared_pending_claim");

            var retired = member;
            var retiredSource = retired.createCommandSourceStack().withPermission(2);
            server.getPlayerList().remove(member);
            connection.finishAndReleaseAll(); connection = null;
            try { service.claims(retiredSource); throw new AssertionError("Retired operator session accepted"); }
            catch (IllegalStateException expected) { check(true, "disconnected_operator_cannot_administer_reward_journal"); }
            check(service.reconcile(source) == 1 && economy.balance(profile.getId(), currency.id()) == memberBefore + 375
                    && economy.balance(owner.getUUID(), currency.id()) == ownerBefore,
                    "offline_reconciliation_pays_exact_original_party_beneficiary");
            var paid = claims(service, source, shared).getFirst();
            check(paid.state() == State.PAID && paid.transactionId() != null && paid.id().equals(pending.id()),
                    "offline_settlement_retains_original_immutable_claim_and_receipt");

            member = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
            member.setPos(1, -60, 1); connection = XModRuntimeAudit.connect(member);
            check(file.getTeamData(member).orElseThrow() == team && server.getPlayerList().getPlayer(profile.getId()) == member,
                    "new_connection_reacquires_original_native_party_without_reusing_retired_actor");
            team.claimReward(member, shared.reward(), false); team.claimReward(owner, shared.reward(), false);
            check(service.reconcile(source) == 0 && claims(service, source, shared).size() == 1
                    && economy.balance(profile.getId(), currency.id()) == memberBefore + 375,
                    "both_party_members_and_reconnect_retries_preserve_one_shared_payment");

            var individual = reward(file, chapter, false, false);
            service.bind(source, individual.reward().getCodeString(), currency.id(), amount);
            team.setProgress(individual.task(), individual.task().getMaxProgress());
            int[] nativeCalls = {0};
            try {
                FtbMonetaryRewards.nativeClaim(team, individual.reward(), retired, () -> { nativeCalls[0]++; return true; });
                throw new AssertionError("Replaced same-UUID claimant accepted");
            } catch (IllegalStateException expected) {
                check(nativeCalls[0] == 0 && claims(service, source, individual).isEmpty(),
                        "replaced_same_uuid_claimant_is_rejected_before_native_acceptance_or_journal_write");
            }
            team.claimReward(owner, individual.reward(), false); team.claimReward(member, individual.reward(), false);
            var individualClaims = claims(service, source, individual);
            check(individualClaims.size() == 2 && individualClaims.stream().allMatch(c -> c.state() == State.PAID)
                    && individualClaims.stream().map(c -> c.intent().ownerId()).distinct().count() == 2
                    && individualClaims.stream().allMatch(c -> c.intent().ownerId().equals(c.intent().beneficiaryId()))
                    && individualClaims.stream().map(c -> c.intent().operationKey()).distinct().count() == 2,
                    "individual_native_reward_creates_separate_member_owned_intents_and_receipts");
            check(economy.balance(owner.getUUID(), currency.id()) == ownerBefore + 375
                    && economy.balance(profile.getId(), currency.id()) == memberBefore + 750,
                    "individual_party_claims_pay_each_native_beneficiary_once");
            team.claimReward(owner, individual.reward(), false); team.claimReward(member, individual.reward(), false);
            check(claims(service, source, individual).size() == 2 && service.reconcile(source) == 0,
                    "individual_duplicate_claims_do_not_create_additional_payment_generations");

            var repeat = reward(file, chapter, true, true);
            service.bind(source, repeat.reward().getCodeString(), currency.id(), amount);
            int cycle = team.getCompletionCount(repeat.task().getQuest());
            team.setProgress(repeat.task(), repeat.task().getMaxProgress());
            team.claimReward(member, repeat.reward(), false);
            var first = claims(service, source, repeat).getFirst();
            check(first.state() == State.PAID && first.intent().nativeCycle() == cycle && first.afterCycle() == cycle + 1
                    && team.getProgress(repeat.task()) == 0,
                    "first_member_repeat_claim_retains_pre_reset_cycle_and_native_reset");
            team.claimReward(owner, repeat.reward(), false);
            check(claims(service, source, repeat).size() == 1,
                    "other_member_cannot_claim_new_repeat_generation_without_native_completion");
            team.setProgress(repeat.task(), repeat.task().getMaxProgress());
            team.claimReward(owner, repeat.reward(), false);
            var repeats = claims(service, source, repeat);
            check(repeats.size() == 2 && repeats.getLast().intent().nativeCycle() == cycle + 1
                    && repeats.getLast().intent().beneficiaryId().equals(owner.getUUID())
                    && first.intent().beneficiaryId().equals(profile.getId())
                    && !first.intent().operationKey().equals(repeats.getLast().intent().operationKey())
                    && team.getCompletionCount(repeat.task().getQuest()) == cycle + 2,
                    "different_member_earns_distinct_completed_repeat_cycle_without_redirecting_previous_receipt");

            var leaving = reward(file, chapter, false, false);
            service.bind(source, leaving.reward().getCodeString(), currency.id(), amount);
            team.setProgress(leaving.task(), leaving.task().getMaxProgress());
            YuuniverseEconomyApi.clear(economy);
            try { team.claimReward(member, leaving.reward(), false); }
            finally { YuuniverseEconomyApi.publish(economy); }
            var leaveClaim = claims(service, source, leaving).getFirst();
            party.leave(profile.getId());
            var personal = file.getTeamData(member).orElseThrow();
            check(personal != team && personal.getTeamId().equals(profile.getId())
                    && leaveClaim.state() == State.ACCEPTED && leaveClaim.intent().teamId().equals(team.getTeamId()),
                    "pending_claim_keeps_original_party_generation_after_native_leave");
            check(service.reconcile(source) == 1 && economy.balance(profile.getId(), currency.id()) == memberBefore + 1500
                    && economy.balance(owner.getUUID(), currency.id()) == ownerBefore + 750,
                    "post_leave_settlement_keeps_original_beneficiary_and_all_exact_party_totals");
            var staleTeam = reward(file, chapter, false, false);
            service.bind(source, staleTeam.reward().getCodeString(), currency.id(), amount);
            team.setProgress(staleTeam.task(), staleTeam.task().getMaxProgress());
            try { team.claimReward(member, staleTeam.reward(), false); throw new AssertionError("Retired party claim accepted"); }
            catch (IllegalStateException expected) {
                check(claims(service, source, staleTeam).isEmpty() && economy.balance(profile.getId(), currency.id()) == memberBefore + 1500,
                        "former_member_cannot_claim_using_old_party_object_or_progress");
            }
            check(service.reconcile(source) == 0 && ((net.yuuniverse.economy.EconomyRuntime) economy).ledger().audit().valid(),
                    "all_party_reconnect_repeat_receipts_are_stable_and_native_ledger_remains_balanced");
            org.slf4j.LoggerFactory.getLogger("citizens").info("[PARTYREWARDAUDIT] COMPLETE {} checks", passed);
        } finally {
            if (party.getMembers().contains(profile.getId())) party.leave(profile.getId());
            if (server.getPlayerList().getPlayer(profile.getId()) == member) server.getPlayerList().remove(member);
            if (connection != null) connection.finishAndReleaseAll();
        }
    }

    private static RewardFixture reward(ServerQuestFile file, Chapter chapter, boolean shared, boolean repeat) {
        var quest = new Quest(file.newID(), chapter);
        var definition = new CompoundTag(); definition.putBoolean("can_repeat", repeat); definition.putInt("repeat_cooldown", 0);
        quest.readData(definition, file.server.registryAccess()); quest.onCreated();
        var task = new CustomTask(file.newID(), quest); task.onCreated();
        var reward = new CustomReward(file.newID(), quest);
        var config = new CompoundTag(); config.putBoolean("team_reward", shared);
        reward.readData(config, file.server.registryAccess()); reward.onCreated();
        file.clearCachedData(); file.clearCachedProgress();
        return new RewardFixture(task, reward);
    }
    private static List<Claim> claims(FtbMonetaryRewards service, net.minecraft.commands.CommandSourceStack source, RewardFixture fixture) {
        return service.claims(source).stream().filter(c -> c.intent().binding().rewardId().equals(fixture.reward().getCodeString())).toList();
    }
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++; org.slf4j.LoggerFactory.getLogger("citizens").info("[PARTYREWARDAUDIT] PASS {}", name);
    }
}
