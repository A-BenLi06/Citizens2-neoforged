package net.citizensnpcs.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.google.gson.JsonArray;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Conservative recovery for Citizens delivery calls; it never replays item removal or task completion. */
public final class QuestDeliveryRecovery implements AutoCloseable {
    private static final String CHECKPOINT = "citizens:quest_delivery_checkpoint";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(QuestDeliveryRecovery.class);
    private static QuestDeliveryRecovery current;
    private final MinecraftServer server;
    private final QuestDeliveryJournal journal;
    private boolean closed;
    private QuestDeliveryRecovery(MinecraftServer server, Path path) throws IOException {
        this.server = server; journal = QuestDeliveryJournal.open(path);
    }
    public static QuestDeliveryRecovery current(MinecraftServer server) {
        QuestDeliveryRecovery service = current;
        if (service == null || service.server != server) throw new IllegalStateException("Quest delivery recovery is unavailable");
        service.active(); return service;
    }
    void check(ServerPlayer actor, QuestDeliveryJournal.Target target, boolean recordMismatch) {
        active();
        for (var entry : journal.entries()) {
            boolean actorOwns = entry.actorId().equals(actor.getUUID());
            boolean taskOwns = entry.before().teamId().equals(target.teamId()) && entry.before().taskId().equals(target.taskId());
            if (QuestDeliveryJournal.pending(entry) && (actorOwns || taskOwns))
                throw new IllegalStateException("Delivery " + entry.id() + " requires its native outcome or operator review");
        }
        var latestActor = journal.latest(actor.getUUID()).orElse(null);
        if (latestActor != null && latestActor.state() == QuestDeliveryJournal.State.CONFIRMED) {
            if (!actor.getPersistentData().getString(CHECKPOINT).equals(latestActor.id().toString()))
                mismatch(latestActor, "Saved player delivery checkpoint does not match the retained outcome", recordMismatch);
        }
        // A teammate can restore/use a different player file. The team's task fence
        // must therefore be checked independently of the current actor checkpoint.
        var latestTask = journal.entries().stream().filter(entry -> entry.before().teamId().equals(target.teamId())
                && entry.before().taskId().equals(target.taskId())).max(java.util.Comparator.comparingLong(QuestDeliveryJournal.Entry::sequence)).orElse(null);
        if (latestTask != null && latestTask.state() == QuestDeliveryJournal.State.CONFIRMED) verify(latestTask, recordMismatch);
    }
    private void verify(QuestDeliveryJournal.Entry entry, boolean recordMismatch) {
        QuestDeliveryJournal.Target live;
        try { live = FtbQuestTasks.deliveryTarget(server, entry.after().teamId(), entry.after().taskId()); }
        catch (RuntimeException unavailable) { mismatch(entry, "The admitted delivery task/team/definition is unavailable", recordMismatch); return; }
        if (!live.retains(entry.after())) mismatch(entry, "Native delivery progress, cycle or definition no longer retains the outcome", recordMismatch);
    }
    private void mismatch(QuestDeliveryJournal.Entry entry, String reason, boolean recordMismatch) {
        if (recordMismatch) write(() -> journal.review(entry.id(), reason));
        throw new IllegalStateException("Delivery " + entry.id() + " requires review: " + reason);
    }
    QuestDeliveryJournal.Entry prepare(ServerPlayer actor, QuestDeliveryJournal.Target target) {
        active(); return write(() -> journal.prepare(actor.getUUID(), target, inventory(actor)));
    }
    void confirm(QuestDeliveryJournal.Entry entry, ServerPlayer actor, QuestDeliveryJournal.Target target) {
        active();
        write(() -> journal.confirm(entry.id(), target, inventory(actor)));
        // This marker is saved with the player's actual inventory by Minecraft. It
        // does not assert that either this player file or FTB's state is saved now.
        actor.getPersistentData().putString(CHECKPOINT, entry.id().toString());
    }
    void interrupted(QuestDeliveryJournal.Entry entry, Throwable original) {
        try { write(() -> journal.review(entry.id(), original.getClass().getName() + ": " + original.getMessage())); }
        catch (RuntimeException | Error recordingFailure) { original.addSuppressed(recordingFailure); }
    }
    public List<QuestDeliveryJournal.Entry> entries(CommandSourceStack source) { authority(source, false); return journal.entries(); }
    public QuestDeliveryJournal.Entry resolve(CommandSourceStack source, UUID id, boolean accepted, String reason) {
        authority(source, true);
        return write(() -> journal.resolve(id, source.getPlayer().getUUID(), accepted, reason));
    }
    private void authority(CommandSourceStack source, boolean requirePlayer) {
        active();
        if (source.getServer() != server || !net.citizensnpcs.api.util.PermissionUtil.hasPermission(source, "citizens.admin"))
            throw new IllegalStateException("Quest delivery review requires administrator authority");
        ServerPlayer player = source.getPlayer();
        if (player != null && (player.hasDisconnected() || server.getPlayerList().getPlayer(player.getUUID()) != player))
            throw new IllegalStateException("Quest delivery operator session has retired");
        if (requirePlayer && player == null) throw new IllegalStateException("Delivery resolution requires an authenticated operator UUID");
    }
    private static String inventory(ServerPlayer player) {
        var values = new JsonArray(); var ops = player.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            values.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, player.getInventory().getItem(slot)).getOrThrow());
        return values.toString();
    }
    private interface Write<T> { T run() throws IOException; }
    private <T> T write(Write<T> action) {
        active(); try { return action.run(); }
        catch (IOException failure) { throw new IllegalStateException("Quest delivery journal is unavailable; retain its files for review", failure); }
    }
    private void active() { if (closed || !server.isSameThread()) throw new IllegalStateException("Quest delivery recovery requires its active server thread"); }
    @Override public void close() throws IOException { active(); closed = true; journal.close(); }
    @EventBusSubscriber(modid = "citizens")
    public static final class Lifecycle {
        @SubscribeEvent public static void start(ServerAboutToStartEvent event) {
            try { current = new QuestDeliveryRecovery(event.getServer(), event.getServer().getWorldPath(LevelResource.ROOT).resolve("citizens/quest-deliveries")); }
            catch (IOException | RuntimeException failure) { current = null; LOGGER.error("Quest delivery journal is unavailable; delivery calls will be rejected", failure); }
        }
        @SubscribeEvent public static void stop(ServerStoppingEvent event) {
            var service = current; current = null;
            if (service != null) try { service.close(); }
            catch (IOException | RuntimeException failure) { LOGGER.error("Quest delivery journal close failed", failure); }
        }
    }
}
