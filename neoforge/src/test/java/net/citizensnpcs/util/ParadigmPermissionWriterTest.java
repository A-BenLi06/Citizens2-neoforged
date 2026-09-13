package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.citizensnpcs.api.util.PermissionUtil;
import net.minecraft.server.level.ServerPlayer;
import net.citizensnpcs.util.ParadigmPermissionWriter.Rule;
import org.junit.jupiter.api.Test;

class ParadigmPermissionWriterTest {
    private static final UUID PLAYER = UUID.randomUUID();

    private static ParadigmPermissionWriter.Change change(Store store, boolean grant, String... permissions) {
        return new ParadigmPermissionWriter.Change(store, PLAYER, List.of(permissions), grant, () -> true, () -> true);
    }

    @Test void preparationAndEligibilityDoNotWrite() {
        Store store = new Store();
        var grant = change(store, true, "audit.new");
        assertTrue(grant.isPossible()); assertTrue(grant.isPossible());
        assertTrue(store.rows.isEmpty()); assertEquals(0, store.adds);
        assertFalse(change(store, false, "audit.inherited").isPossible());
        assertEquals(0, store.removes.size());
    }

    @Test void grantRollbackKeepsPreexistingAndUnrelatedRecords() {
        Store store = new Store(); store.seed("audit.existing"); store.seed("other.permission");
        Set<Rule> before = Set.copyOf(store.rows);
        var grant = change(store, true, "audit.existing", "audit.new");
        grant.apply();
        assertEquals(3, store.rows.size());
        grant.rollback(); grant.rollback();
        assertEquals(before, store.rows); assertEquals(1, store.adds);
        assertEquals(1, store.removes.get("audit.new"));
    }

    @Test void rejectedSecondGrantCompensatesFirstBeforeFailureEscapes() {
        Store store = new Store(); store.seed("audit.existing"); store.rejectAdd = "audit.second";
        Set<Rule> before = Set.copyOf(store.rows);
        var grant = change(store, true, "audit.existing", "audit.first", "audit.second");
        assertThrows(IllegalStateException.class, grant::apply);
        assertEquals(before, store.rows);
        grant.rollback(); assertEquals(before, store.rows);
    }

    @Test void rejectedSecondTakeRestoresFirstAndKeepsOtherGrants() {
        Store store = new Store(); store.seed("audit.first"); store.seed("audit.second"); store.seed("audit.other");
        store.rejectRemove = "audit.second";
        Set<Rule> before = Set.copyOf(store.rows);
        var take = change(store, false, "audit.first", "audit.second");
        assertTrue(take.isPossible());
        assertThrows(IllegalStateException.class, take::apply);
        assertEquals(before, store.rows);
    }

    @Test void takeRechecksTheRecordAfterEligibility() {
        Store store = new Store(); store.seed("audit.token");
        var take = change(store, false, "audit.token");
        assertTrue(take.isPossible()); store.rows.clear();
        assertThrows(IllegalStateException.class, take::apply);
        take.rollback(); assertTrue(store.rows.isEmpty());
    }

    @Test void takeRequiresEffectiveEligibilityAndADirectToken() {
        Store store = new Store(); store.seed("audit.token");
        var denied = new ParadigmPermissionWriter.Change(store, PLAYER, List.of("audit.token"), false, () -> true, () -> false);
        assertFalse(denied.isPossible());
        assertFalse(change(store, false, "audit.inherited-only").isPossible());
        assertEquals(Set.of(Store.record("audit.token")), store.rows);
    }

    @Test void aSuccessfulTakeRestoresTheOriginalProviderRecord() {
        Store store = new Store(); store.seed("audit.token");
        var take = change(store, false, "audit.token");
        take.apply(); assertTrue(store.rows.isEmpty());
        take.rollback(); take.rollback();
        assertEquals(Set.of(Store.record("audit.token")), store.rows);
        assertEquals(1, store.adds);
    }

    @Test void aChangedGrantIsNotOverwrittenDuringRefund() {
        Store store = new Store(); store.seed("audit.token");
        var take = change(store, false, "audit.token"); take.apply();
        Rule replacement = new Rule("new-owner", "audit.token"); store.rows.add(replacement);
        assertThrows(IllegalStateException.class, take::rollback);
        assertEquals(Set.of(replacement), store.rows); assertEquals(0, store.adds);
    }

    @Test void aReplacedAddedRecordIsNotDeletedByRollback() {
        Store store = new Store(); var grant = change(store, true, "audit.token"); grant.apply();
        store.rows.clear(); Rule replacement = new Rule("other-owner", "audit.token"); store.rows.add(replacement);
        grant.rollback(); assertEquals(Set.of(replacement), store.rows);
    }

    @Test void rollbackRetriesOnlyItsUnfinishedParts() {
        Store store = new Store(); var grant = change(store, true, "audit.first", "audit.second"); grant.apply();
        store.rejectRemove = "audit.second";
        assertThrows(IllegalStateException.class, grant::rollback);
        assertEquals(Set.of(Store.record("audit.second")), store.rows);
        store.rejectRemove = null; grant.rollback(); grant.rollback();
        assertTrue(store.rows.isEmpty());
        assertEquals(1, store.removes.get("audit.first")); assertEquals(2, store.removes.get("audit.second"));
    }

    @Test void failedReceiptReadStillCompensatesAnAcknowledgedGrant() {
        Store store = new Store(); store.failReadAfterAdd = true;
        var grant = change(store, true, "audit.token");
        assertThrows(IllegalStateException.class, grant::apply);
        assertTrue(store.rows.isEmpty()); assertEquals(1, store.removes.get("audit.token"));
    }

    @Test void failedMutationDoesNotClaimAnotherWritersGrant() {
        Store store = new Store(); store.concurrentAdd = true;
        var grant = change(store, true, "audit.token");
        assertThrows(IllegalStateException.class, grant::apply);
        assertEquals(Set.of(Store.record("audit.token")), store.rows);
        assertTrue(store.removes.isEmpty());
    }

    @Test void normalizesDeduplicatesAndCopiesPermissionInputs() {
        Store store = new Store(); var permissions = new ArrayList<>(List.of(" Audit.Mixed ", "audit.mixed", "audit.中文.*"));
        var grant = new ParadigmPermissionWriter.Change(store, PLAYER, permissions, true, () -> true, () -> true);
        permissions.clear(); grant.apply();
        assertEquals(Set.of(Store.record("audit.mixed"), Store.record("audit.中文.*")), store.rows);
        assertEquals(2, store.adds);
        assertThrows(IllegalArgumentException.class, () -> change(store, true, " "));
    }

    @Test void unavailableBackendDoesNotStartAndAChangeCannotRunTwice() {
        Store store = new Store(); store.available = false;
        var unavailable = change(store, true, "audit.token");
        assertFalse(unavailable.isPossible()); assertThrows(IllegalStateException.class, unavailable::apply);
        assertTrue(store.rows.isEmpty());
        store.available = true; unavailable.apply();
        assertFalse(unavailable.isPossible()); assertThrows(IllegalStateException.class, unavailable::apply);
        unavailable.rollback(); assertTrue(store.rows.isEmpty());
    }

    @Test void unavailableProviderIsNotInvokedByScalarHelpers() {
        var before = PermissionUtil.getPermissionWriter();
        try {
            PermissionUtil.setPermissionWriter(new PermissionUtil.PermissionWriter() {
                public boolean isAvailable() { return false; }
                public boolean add(ServerPlayer player, String permission) { return fail("Unavailable writer was invoked"); }
                public boolean remove(ServerPlayer player, String permission) { return fail("Unavailable writer was invoked"); }
            });
            assertFalse(PermissionUtil.canWritePermissions());
            assertFalse(PermissionUtil.addPermission(null, "audit.node"));
            assertFalse(PermissionUtil.removePermission(null, "audit.node"));
        } finally { PermissionUtil.setPermissionWriter(before); }
    }

    @Test void signedRulesKeepTheirOwnIdentityAndPreserveOppositePolarity() {
        Store store = new Store(); store.seed("audit.access");
        var deny = change(store, true, " - AUDIT.ACCESS ");
        deny.apply();
        assertEquals(Set.of(Store.record("audit.access"), Store.record("-audit.access")), store.rows);
        deny.rollback();
        assertEquals(Set.of(Store.record("audit.access")), store.rows);
        assertThrows(IllegalArgumentException.class, () -> change(store, true, " - "));
    }

    @Test void detachingTheWriterPreventsNewChangesButDoesNotDiscardUndo() {
        Store store = new Store(); AtomicBoolean active = new AtomicBoolean(true);
        var grant = new ParadigmPermissionWriter.Change(store, PLAYER, List.of("audit.token"), true, active::get, () -> true);
        grant.apply(); active.set(false); grant.rollback();
        assertTrue(store.rows.isEmpty());
        var next = new ParadigmPermissionWriter.Change(store, PLAYER, List.of("audit.next"), true, active::get, () -> true);
        assertFalse(next.isPossible());
    }

    private static final class Store implements ParadigmPermissionWriter.Backend {
        final Set<Rule> rows = new LinkedHashSet<>();
        final Map<String, Integer> removes = new HashMap<>();
        String rejectAdd, rejectRemove;
        boolean available = true, failReadAfterAdd, failNextRead, concurrentAdd;
        int adds;
        static Rule record(String node) { return new Rule("id:" + node, node); }
        void seed(String node) { rows.add(record(node)); }
        public boolean available() { return available; }
        public Set<Rule> rules(UUID player) {
            if (failNextRead) { failNextRead = false; throw new IllegalStateException("Injected receipt read failure"); }
            return Set.copyOf(rows);
        }
        public boolean add(UUID player, String permission) {
            adds++;
            if (permission.equals(rejectAdd)) return false;
            if (concurrentAdd) { seed(permission); return false; }
            boolean changed = rows.add(record(permission));
            if (failReadAfterAdd) { failReadAfterAdd = false; failNextRead = true; }
            return changed;
        }
        public boolean remove(UUID player, Rule grant) {
            removes.merge(grant.permission(), 1, Integer::sum);
            return !grant.permission().equals(rejectRemove) && rows.remove(grant);
        }
    }
}
