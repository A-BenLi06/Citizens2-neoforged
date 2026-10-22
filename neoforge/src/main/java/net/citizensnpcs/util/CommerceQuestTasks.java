package net.citizensnpcs.util;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.citizensnpcs.util.CommerceTaskJournal.*;

/** Native pre-trade attribution plus durable, absolute receipt projection into explicitly owned CustomTasks. */
public final class CommerceQuestTasks implements AutoCloseable {
    private static final String API = "net.yuuniverse.economy.api.", CORE = "net.yuuniverse.economy.core.";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CommerceQuestTasks.class);
    private static CommerceQuestTasks current;
    private final MinecraftServer server;
    private final CommerceTaskJournal journal;
    private final List<Consumer<Event>> listeners = new ArrayList<>();
    private final Set<UUID> processing = new HashSet<>();
    private boolean closed;
    private int playerCursor;

    public static CommerceQuestTasks current(MinecraftServer server) {
        if (current == null || current.server != server) throw new IllegalStateException("Commerce task journal is unavailable"); current.active(); return current;
    }
    static boolean owns(MinecraftServer server, String taskId) {
        return current(server).journal.bindings().stream().anyMatch(value -> value.taskId().equals(QuestRewardJournal.objectId(taskId)));
    }
    private CommerceQuestTasks(MinecraftServer server, Path path) throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Commerce tasks require the server thread");
        this.server = server; journal = CommerceTaskJournal.open(path);
        try {
            optionalListener(API + "CommerceStartingEvent", this::starting);
            optionalListener(API + "CommerceCommittedEvent", this::committed);
        } catch (RuntimeException | Error failure) {
            for (var listener : listeners) NeoForge.EVENT_BUS.unregister(listener);
            try { journal.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); } throw failure;
        }
    }
    @SuppressWarnings({"unchecked", "rawtypes"}) private void optionalListener(String name, Consumer<Event> dispatch) {
        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(name);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, (Class) type, dispatch); listeners.add(dispatch);
        } catch (ClassNotFoundException | LinkageError absent) { /* Provider capability is optional; binding requires revision 3. */ }
    }
    public Binding bind(CommandSourceStack source, String taskId, Shop shopType, String shopId, String itemKey,
            Action action, Measure measure, String currencyId, boolean requireDelivery) throws IOException {
        authorize(source, true); var actor = source.getPlayer();
        var target = FtbQuestTasks.evidenceTarget(actor, taskId);
        if (!target.visible() || target.state().maximum() <= 0) throw new IllegalArgumentException("Task is unavailable");
        if (CraftQuestEvidence.current(server).bindings(source).stream().anyMatch(value -> value.taskId().equals(target.state().taskId())))
            throw new IllegalStateException("Task is already owned by craft evidence");
        var provider = provider(actor); Position position = position(provider, actor);
        reflected(() -> {
            Class<?> economy = Class.forName(API + "EconomyApi"); Object api = economy();
            if (shopType == Shop.SYSTEM) {
                Object shops = ((Optional<?>) economy.getMethod("systemShops").invoke(api)).orElseThrow();
                var type = Class.forName(API + "SystemShopApi"); boolean found = false;
                for (Object page : (List<?>) type.getMethod("pages", UUID.class, String.class).invoke(shops, actor.getUUID(), shopId))
                    for (Object offer : (List<?>) type.getMethod("offers", UUID.class, String.class, String.class).invoke(shops, actor.getUUID(), shopId, property(page, "pageKey")))
                        if (Objects.equals(property(offer, "itemKey"), itemKey)) found = true;
                if (!found) throw new IllegalArgumentException("System offer is unavailable");
            } else {
                Object shops = ((Optional<?>) economy.getMethod("coordinateShops").invoke(api)).orElseThrow();
                Object reference = Class.forName(API + "CoordinateShopReference$Id").getConstructor(String.class).newInstance(shopId);
                if (((Optional<?>) Class.forName(API + "CoordinateShopApi").getMethod("shop", UUID.class, Class.forName(API + "CoordinateShopReference"))
                        .invoke(shops, actor.getUUID(), reference)).isEmpty()) throw new IllegalArgumentException("Coordinate shop is unavailable");
            }
            if (measure == Measure.MINOR_UNITS) {
                Object currency = ((Optional<?>) economy.getMethod("resolveCurrency", String.class).invoke(api, currencyId)).orElseThrow();
                if (!Objects.equals(property(currency, "id"), currencyId)) throw new IllegalArgumentException("Use the exact currency ID");
            }
            return null;
        });
        Binding previous = journal.bindings().stream().filter(value -> value.taskId().equals(target.state().taskId())).findFirst().orElse(null);
        if (previous != null) {
            validate(previous, target);
            if (!previous.journalId().equals(position.id()) || previous.shopType() != shopType || !previous.shopId().equals(shopId)
                    || !previous.itemKey().equals(itemKey) || previous.action() != action || previous.measure() != measure
                    || !Objects.equals(previous.currencyId(), currencyId) || previous.requireDelivery() != requireDelivery)
                throw new IllegalStateException("Commerce binding is immutable; migrate content explicitly");
            return previous;
        }
        if (target.state().progress() != 0 || target.state().completed()) throw new IllegalStateException("Task already has unowned progress");
        return journal.bind(new Binding(UUID.randomUUID(), target.state().taskId(), target.questId(), FtbQuestTasks.evidenceRevision(server, target.state().teamId(), target.state().taskId()), target.state().maximum(), target.repeatable(),
                position.id(), position.sequence(), shopType, shopId, itemKey, action, measure, currencyId, requireDelivery, actor.getUUID(), System.currentTimeMillis()));
    }
    private void starting(Event event) {
        if (closed || journal.bindings().isEmpty()) return;
        try {
            active(); ServerPlayer actor = (ServerPlayer) property(event, "player"); online(actor);
            Object nativeRequest = property(event, "request"), nativePosition = property(event, "journalPosition");
            var request = request(nativeRequest, actor); var position = new Position((UUID) property(nativePosition, "journalId"), ((Number) property(nativePosition, "sequence")).longValue());
            validateRetained(provider(actor), actor, position);
            for (Binding binding : journal.bindings()) if (CommerceTaskJournal.matches(binding, request)) {
                try {
                    journal.cursor(binding, actor.getUUID(), position.id(), position.sequence());
                    var target = FtbQuestTasks.evidenceTarget(actor, binding.taskId()); validate(binding, target);
                    if (!target.visible() || !target.state().available() || target.state().completed()) continue;
                    long applied = journal.total(binding, target.state().teamId(), target.cycle(), true);
                    if (target.state().progress() != applied) throw new IllegalStateException("Task has unowned or unacknowledged progress");
                    journal.prepare(binding, request, new Attribution(target.state().teamId(), target.cycle(), position.sequence(), System.currentTimeMillis()));
                } catch (IOException | RuntimeException failure) { LOGGER.error("Commerce attribution failed for task {}; committed receipts remain reviewable", binding.taskId(), failure); }
            }
        } catch (RuntimeException failure) { LOGGER.error("Commerce starting observation is unavailable", failure); }
    }
    /** Commit events only wake replay; provider history and current outcome remain authoritative. */
    private void committed(Event event) {
        if (closed || journal.bindings().isEmpty()) return;
        try {
            active(); String account = (String) property(property(event, "entry"), "playerAccountId");
            for (ServerPlayer actor : server.getPlayerList().getPlayers()) if (account.equals(account(actor))) { replay(actor); break; }
        } catch (IOException | RuntimeException failure) { LOGGER.error("Committed commerce task replay needs reconciliation", failure); }
    }
    public int replay(ServerPlayer actor) throws IOException {
        active(); online(actor); if (!processing.add(actor.getUUID())) throw new IllegalStateException("Commerce replay is already active for this actor");
        try {
            Object provider = provider(actor); Position position = position(provider, actor); int count = 0;
            validateRetained(provider, actor, position);
            for (Binding binding : journal.bindings()) {
                long cursor = journal.cursor(binding, actor.getUUID(), position.id(), position.sequence());
                List<?> page = reflected(() -> (List<?>) Class.forName(API + "CommerceApi").getMethod("history", UUID.class, long.class, int.class).invoke(provider, actor.getUUID(), cursor, 100));
                long last = cursor;
                for (Object value : page) {
                    long sequence = ((Number) property(value, "sequence")).longValue();
                    if (sequence <= last || sequence > position.sequence()) throw new IllegalStateException("Invalid provider history order"); last = sequence;
                    var request = request(value, actor);
                    if (!CommerceTaskJournal.matches(binding, request)) continue;
                    Object nativeReceipt = property(value, "receipt"); Receipt receipt = receipt(nativeReceipt, value);
                    Object outcome = reflected(() -> ((Optional<?>) Class.forName(API + "CommerceApi").getMethod("findOperation", UUID.class, UUID.class)
                            .invoke(provider, actor.getUUID(), receipt.operationId())).orElseThrow(() -> new IllegalStateException("Committed operation disappeared")));
                    if (!receipt(receiptFromOutcome(outcome), value).equals(receipt)) throw new IllegalStateException("History/outcome receipt differs");
                    boolean confirmed = settled(binding, outcome)
                            && (binding.measure() != Measure.TRADES || receipt.trades() != null)
                            && (binding.measure() != Measure.MINOR_UNITS || binding.currencyId().equals(receipt.currencyId()));
                    Entry entry = journal.receipt(binding, request, receipt, confirmed);
                    count++;
                }
                journal.checkpoint(binding, actor.getUUID(), position.id(), position.sequence(), last);
            }
            // Checkpoint only after retaining each matching receipt. Delivery can settle after its page was checkpointed.
            var scopes = new LinkedHashSet<Projection>();
            for (Entry entry : journal.entries()) if (entry.request().actorId().equals(actor.getUUID())) {
                if (entry.state() == State.WAITING) {
                    Object outcome = outcome(provider, actor, entry.receipt().operationId());
                    validateOutcome(entry.receipt(), outcome);
                    if (settled(entry.binding(), outcome)) entry = journal.ready(entry.id());
                }
                if (entry.state() == State.CONFIRMED || entry.state() == State.APPLIED)
                    scopes.add(new Projection(entry.binding(), entry.attribution().teamId(), entry.attribution().cycle()));
            }
            for (Projection scope : scopes) try { project(scope.binding(), scope.team(), scope.cycle()); }
            catch (IOException | RuntimeException failure) { LOGGER.warn("Commerce projection remains pending for task {}, team {}, cycle {}: {}",
                    scope.binding().taskId(), scope.team(), scope.cycle(), failure.getMessage()); }
            return count;
        } finally { processing.remove(actor.getUUID()); }
    }
    private static Object receiptFromOutcome(Object outcome) { return property(outcome, "receipt"); }
    private record Projection(Binding binding, UUID team, int cycle) { }
    private static Object outcome(Object provider, ServerPlayer actor, UUID operation) {
        return reflected(() -> ((Optional<?>) Class.forName(API + "CommerceApi").getMethod("findOperation", UUID.class, UUID.class)
                .invoke(provider, actor.getUUID(), operation)).orElseThrow(() -> new IllegalStateException("Committed operation disappeared")));
    }
    private static void validateOutcome(Receipt expected, Object outcome) {
        Object actual = receiptFromOutcome(outcome);
        if (!new Receipt((UUID) property(actual, "operationId"), (UUID) property(actual, "transactionId"), (UUID) property(actual, "deliveryClaimId"),
                expected.sequence(), (String) property(actual, "currencyId"), ((Number) property(actual, "totalMinorUnits")).longValue(),
                ((Number) property(actual, "quantity")).intValue(), expected.trades(), (String) property(actual, "itemSnapshot"),
                (String) property(actual, "legacyMaterial"), ((Number) property(actual, "createdAtEpochMillis")).longValue()).equals(expected))
            throw new IllegalStateException("Provider receipt changed its immutable payload");
    }
    private static boolean settled(Binding binding, Object outcome) {
        String state = ((Enum<?>) property(outcome, "state")).name();
        return state.equals("COMMITTED") || !binding.requireDelivery() && state.equals("DELIVERY_PENDING");
    }
    /** A restored ledger can catch up its sequence with unrelated trades while losing admitted settlements. */
    private void validateRetained(Object provider, ServerPlayer actor, Position position) {
        for (Binding binding : journal.bindings()) journal.cursor(binding, actor.getUUID(), position.id(), position.sequence());
        for (Entry entry : journal.entries()) if (entry.request().actorId().equals(actor.getUUID())
                && (entry.state() == State.WAITING || entry.state() == State.CONFIRMED || entry.state() == State.APPLIED)) {
            Object outcome = outcome(provider, actor, entry.receipt().operationId());
            validateOutcome(entry.receipt(), outcome);
            if (entry.state() != State.WAITING && !settled(entry.binding(), outcome))
                throw new IllegalStateException("Retained receipt inventory outcome is unresolved");
        }
    }
    private void project(Binding binding, UUID team, int cycle) throws IOException {
        var target = FtbQuestTasks.evidenceTarget(server, team, binding.taskId()); validate(binding, target);
        journal.validateCycle(binding, team, target.cycle());
        // Fully acknowledged earlier cycles need no mutation after a normal native repeat reset.
        if (target.cycle() > cycle && journal.entries().stream().noneMatch(entry -> entry.binding().equals(binding)
                && entry.attribution() != null && entry.attribution().teamId().equals(team) && entry.attribution().cycle() == cycle
                && entry.state() == State.CONFIRMED)) return;
        if (target.cycle() != cycle) throw new IllegalStateException("Historical receipt cycle differs from native FTB; review required");
        long total = journal.total(binding, team, cycle, false), applied = journal.total(binding, team, cycle, true);
        if (target.state().progress() == total && applied == total) return;
        if (target.state().progress() > applied && target.state().progress() != total) throw new IllegalStateException("Native progress conflicts with admitted receipts");
        // A crash can persist APPLIED before FTB's own save. Verify the retained settlements again before restoring.
        for (Entry entry : journal.entries()) if (entry.binding().equals(binding)
                && entry.attribution() != null && entry.attribution().teamId().equals(team) && entry.attribution().cycle() == cycle
                && (entry.state() == State.CONFIRMED || target.state().progress() < applied && entry.state() == State.APPLIED)) {
            var actor = server.getPlayerList().getPlayer(entry.request().actorId()); online(actor);
            Object provider = provider(actor); Position position = position(provider, actor);
            journal.cursor(binding, actor.getUUID(), position.id(), position.sequence());
            Object outcome = outcome(provider, actor, entry.receipt().operationId()); validateOutcome(entry.receipt(), outcome);
            if (!settled(binding, outcome)) throw new IllegalStateException("Retained receipt inventory outcome is unresolved");
        }
        FtbQuestTasks.projectEvidence(server, team, binding.taskId(), binding.questId(), cycle, binding.maximum(), target.state().progress(), total);
        for (Entry entry : journal.entries()) if (entry.binding().equals(binding) && entry.attribution() != null
                && entry.attribution().teamId().equals(team) && entry.attribution().cycle() == cycle && entry.state() == State.CONFIRMED) journal.applied(entry.id());
    }
    public Entry resolve(CommandSourceStack source, String id, UUID teamId, int cycle, boolean accept, String reason) throws IOException {
        authorize(source, true); Entry entry = journal.entry(id); var binding = entry.binding();
        var actor = server.getPlayerList().getPlayer(entry.request().actorId()); online(actor);
        Object provider = provider(actor); Position position = position(provider, actor); journal.cursor(binding, actor.getUUID(), position.id(), position.sequence());
        validateRetained(provider, actor, position);
        Object outcome = outcome(provider, actor, entry.receipt().operationId()); validateOutcome(entry.receipt(), outcome);
        if (accept && !settled(binding, outcome)) throw new IllegalStateException("Provider inventory outcome is still unresolved");
        Attribution attribution = entry.attribution();
        if (attribution != null && (!attribution.teamId().equals(teamId) || attribution.cycle() != cycle)) throw new IllegalStateException("Original trade attribution cannot be redirected");
        if (accept) {
            var target = FtbQuestTasks.evidenceTarget(server, teamId, binding.taskId()); validate(binding, target);
            if (target.cycle() != cycle) throw new IllegalStateException("Review cycle is not current");
            if (attribution == null) attribution = new Attribution(teamId, cycle, binding.startingSequence(), System.currentTimeMillis());
        }
        var resolved = journal.resolve(id, attribution, source.getPlayer().getUUID(), reason, accept);
        if (accept) project(binding, teamId, cycle); return journal.entry(resolved.id());
    }
    public int reconcile(CommandSourceStack source) throws IOException {
        authorize(source, false); int count = 0; for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) count += replay(player); return count;
    }
    public List<Binding> bindings(CommandSourceStack source) { authorize(source, false); return journal.bindings(); }
    public List<Entry> entries(CommandSourceStack source) { authorize(source, false); return journal.entries(); }
    private record Position(UUID id, long sequence) { }
    private static Object economy() { return reflected(() -> ((Optional<?>) Class.forName(API + "YuuniverseEconomyApi").getMethod("current").invoke(null)).orElseThrow(() -> new IllegalStateException("Economy is unavailable"))); }
    private Object provider(ServerPlayer actor) {
        online(actor); Object provider = reflected(() -> ((Optional<?>) Class.forName(API + "EconomyApi").getMethod("commerceApi").invoke(economy())).orElseThrow());
        int version = reflected(() -> (int) Class.forName(API + "CommerceApi").getMethod("version").invoke(provider));
        if (version < 3) throw new IllegalStateException("Commerce capability revision 3 is required"); return provider;
    }
    private static Position position(Object provider, ServerPlayer actor) {
        Object nativePosition = reflected(() -> Class.forName(API + "CommerceApi").getMethod("journalPosition", UUID.class).invoke(provider, actor.getUUID()));
        return new Position((UUID) property(nativePosition, "journalId"), ((Number) property(nativePosition, "sequence")).longValue());
    }
    private static String account(ServerPlayer actor) { return reflected(() -> (String) Class.forName(CORE + "AccountIds").getMethod("player", UUID.class).invoke(null, actor.getUUID())); }
    private static Request request(Object value, ServerPlayer actor) {
        boolean history = value.getClass().getName().equals(CORE + "CommerceJournalEntry");
        if (!Objects.equals(property(value, "playerAccountId"), account(actor))) throw new IllegalStateException("Commerce account does not belong to actor");
        Number trades = (Number) property(value, history ? "tradeQuantity" : "quantity");
        String requestKey = (String) property(value, history ? "requestKey" : "idempotencyKey");
        return new Request(Shop.valueOf(property(value, "shopType").toString()), (String) property(value, "shopId"), (String) property(value, "itemKey"),
                Action.valueOf(property(value, "action").toString()), actor.getUUID(), trades == null ? null : trades.intValue(), requestKey, (String) property(value, "origin"),
                requestKey == null && history ? (UUID) property(property(value, "receipt"), "operationId") : null);
    }
    private static Receipt receipt(Object value, Object metadata) {
        return new Receipt((UUID) property(value, "operationId"), (UUID) property(value, "transactionId"), (UUID) property(value, "deliveryClaimId"), ((Number) property(metadata, "sequence")).longValue(),
                (String) property(value, "currencyId"), ((Number) property(value, "totalMinorUnits")).longValue(), ((Number) property(value, "quantity")).intValue(),
                (Integer) property(metadata, "tradeQuantity"), (String) property(value, "itemSnapshot"), (String) property(value, "legacyMaterial"), ((Number) property(value, "createdAtEpochMillis")).longValue());
    }
    private static Object property(Object value, String name) { return reflected(() -> value.getClass().getMethod(name).invoke(value)); }
    private void validate(Binding binding, FtbQuestTasks.EvidenceTarget target) {
        if (!binding.questId().equals(target.questId()) || binding.maximum() != target.state().maximum() || binding.repeatable() != target.repeatable()
                || !binding.definitionRevision().equals(FtbQuestTasks.evidenceRevision(server, target.state().teamId(), binding.taskId())))
            throw new IllegalStateException("Native commerce task definition changed");
    }
    private void online(ServerPlayer actor) { active(); if (actor == null || actor.getServer() != server || actor.hasDisconnected() || server.getPlayerList().getPlayer(actor.getUUID()) != actor) throw new IllegalStateException("Commerce actor session retired"); }
    private void authorize(CommandSourceStack source, boolean requirePlayer) {
        active(); if (source == null || source.getServer() != server || !PermissionUtil.hasPermission(source, "citizens.admin")) throw new IllegalStateException("Commerce task administration is not permitted");
        if (source.getPlayer() != null) online(source.getPlayer()); else if (requirePlayer) throw new IllegalStateException("Binding/review requires an authenticated operator UUID");
    }
    private interface Query<T> { T run() throws ReflectiveOperationException; }
    private static <T> T reflected(Query<T> query) {
        try { return query.run(); } catch (InvocationTargetException failure) { if (failure.getCause() instanceof RuntimeException cause) throw cause; throw new IllegalStateException("Commerce provider request failed", failure.getCause()); }
        catch (ReflectiveOperationException | LinkageError absent) { throw new IllegalStateException("Commerce provider capability is unavailable", absent); }
    }
    private void active() { if (closed || !server.isSameThread()) throw new IllegalStateException("Commerce tasks require the active server thread"); }
    @Override public void close() throws IOException {
        if (!server.isSameThread()) throw new IllegalStateException("Commerce tasks require the server thread");
        if (closed) return; closed = true; for (var listener : listeners) NeoForge.EVENT_BUS.unregister(listener); journal.close();
    }
    @EventBusSubscriber(modid = "citizens") public static final class Lifecycle {
        @SubscribeEvent(priority = EventPriority.LOWEST) public static void start(ServerStartedEvent event) {
            try { current = new CommerceQuestTasks(event.getServer(), event.getServer().getWorldPath(LevelResource.ROOT).resolve("citizens/commerce-tasks")); }
            catch (IOException | RuntimeException failure) { current = null; LOGGER.error("Commerce task journal unavailable; retained records need review", failure); }
        }
        @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
            if (current == null || current.closed || event.getServer() != current.server || event.getServer().getTickCount() % 20 != 0) return;
            List<ServerPlayer> players = List.copyOf(event.getServer().getPlayerList().getPlayers()); if (players.isEmpty()) return;
            ServerPlayer actor = players.get(Math.floorMod(current.playerCursor++, players.size()));
            try { if (!current.journal.bindings().isEmpty()) current.replay(actor); }
            catch (IOException | RuntimeException failure) { LOGGER.warn("Commerce task replay remains pending for {}: {}", actor.getUUID(), failure.getMessage()); }
        }
        @SubscribeEvent(priority = EventPriority.HIGHEST) public static void stop(ServerStoppingEvent event) {
            var retired = current; current = null; if (retired != null) try { retired.close(); } catch (IOException failure) { LOGGER.error("Commerce journal close failed", failure); }
        }
    }
}
