package net.citizensnpcs.npc.skin;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SkinCacheReloadTest {
    @AfterEach void cleanup() { Skin.clearCache(); }

    @Test void rollbackRestoresPreviousCacheIdentity() {
        Skin previous = Skin.get(""); // Invalid name: exercises cache ownership without a network lookup.
        try (var reload = Skin.beginReload()) {
            assertFalse(Skin.hasSkin(""));
            assertNotSame(previous, Skin.get(""));
        }
        assertSame(previous, Skin.get(""));
    }

    @Test void commitKeepsCandidateAndLateCloseCannotUndoAnotherReload() {
        Skin previous = Skin.get("");
        var completed = Skin.beginReload();
        Skin candidate = Skin.get("");
        completed.commit();
        assertNotSame(previous, candidate);
        assertSame(candidate, Skin.get(""));
        try (var later = Skin.beginReload()) {
            Skin next = Skin.get("");
            completed.close();
            assertSame(next, Skin.get(""));
            assertThrows(IllegalStateException.class, completed::commit);
        }
        assertSame(candidate, Skin.get(""));
    }

    @Test void nestedResetCannotDestroyRollbackCache() {
        Skin previous = Skin.get("");
        try (var reload = Skin.beginReload()) {
            assertThrows(IllegalStateException.class, Skin::beginReload);
            assertThrows(IllegalStateException.class, Skin::clearCache);
        }
        assertSame(previous, Skin.get(""));
    }

    @Test void discardedLookupNeverBecomesCurrentWhenALaterReloadStarts() {
        Skin previous = Skin.get("");
        Skin discarded;
        try (var reload = Skin.beginReload()) {
            assertFalse(previous.isCurrent());
            discarded = Skin.get("");
            assertTrue(discarded.isCurrent());
            Skin.invalidate(""); // A pending lookup can outlive its name's map entry.
        }
        assertTrue(previous.isCurrent());
        assertFalse(discarded.isCurrent());
        try (var later = Skin.beginReload()) {
            assertFalse(discarded.isCurrent());
            assertTrue(Skin.get("").isCurrent());
        }
        assertTrue(previous.isCurrent());
    }
}
