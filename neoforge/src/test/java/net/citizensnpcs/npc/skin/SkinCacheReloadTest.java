package net.citizensnpcs.npc.skin;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SkinCacheReloadTest {
    private static java.util.Optional<com.mojang.authlib.GameProfile> result() {
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "CachedProfile");
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/" + "a".repeat(64) + "\"}}}";
        String texture = java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        profile.getProperties().put("textures", new com.mojang.authlib.properties.Property("textures", texture));
        return java.util.Optional.of(profile);
    }

    @Test void oldLookupResultCanCompleteAfterRollback() {
        Skin previous = Skin.get("");
        var profile = result();
        try (var reload = Skin.beginReload()) { Skin.get(""); }
        previous.acceptLookupResult(profile, null);
        assertTrue(previous.hasSkinData());
        assertEquals(profile.orElseThrow().getId(), previous.getSkinId());
    }

    @Test void discardedLookupResultCannotPopulateCacheDuringLaterAttempt() {
        Skin discarded;
        try (var reload = Skin.beginReload()) {
            discarded = Skin.get("");
            Skin.invalidate("");
        }
        try (var later = Skin.beginReload()) {
            Skin current = Skin.get("");
            discarded.acceptLookupResult(result(), null);
            assertFalse(discarded.hasSkinData());
            assertFalse(current.hasSkinData());
            current.acceptLookupResult(result(), null);
            assertTrue(current.hasSkinData());
        }
    }

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
