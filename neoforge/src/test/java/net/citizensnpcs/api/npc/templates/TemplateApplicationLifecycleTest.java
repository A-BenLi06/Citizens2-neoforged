package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.minecraft.resources.ResourceLocation;

class TemplateApplicationLifecycleTest {
    private static Template template() {
        var key = new MemoryDataKey(); key.setRaw("yaml_replace", Map.of("replacements", Map.of("name", "New")));
        return Template.load(new TemplateRegistry.TemplateErrorReporter(), null,
                ResourceLocation.fromNamespaceAndPath("test", "apply"), key);
    }

    private static class Target {
        boolean spawned = true, allowDespawn = true, allowSpawn = true, registered = true;
        boolean ownsUuid = true;
        int saves, spawns;
        RuntimeException saveFailure, spawnFailure;
        Runnable afterSpawn = () -> {};
        NPC npc;
        Target() {
            this(UUID.randomUUID());
        }
        Target(UUID uuid) {
            NPCRegistry registry = (NPCRegistry) Proxy.newProxyInstance(NPCRegistry.class.getClassLoader(), new Class<?>[]{NPCRegistry.class},
                    (proxy, method, args) -> registered && (!method.getName().equals("getByUniqueId") || ownsUuid) ? npc : null);
            npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isSpawned": return spawned;
                    case "getOwningRegistry": return registry;
                    case "getId": return 1;
                    case "getUniqueId": return uuid;
                    case "getStoredLocation": return null;
                    case "despawn": if (allowDespawn) spawned = false; return allowDespawn;
                    case "spawn": spawns++; if (spawnFailure != null) throw spawnFailure; spawned = allowSpawn; afterSpawn.run(); return allowSpawn;
                    case "saveSnapshot": saves++; if (saveFailure != null) throw saveFailure; return null;
                    case "load": return null;
                    default: throw new AssertionError(method.getName());
                }
            });
        }
    }

    @Test void recursiveApplicationFailsBeforeRepeatingActionsAndCanRetry() {
        var target = new Target(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        Template[] template = new Template[1];
        template[0] = new Template(ResourceLocation.fromNamespaceAndPath("test", "recursive"), java.util.List.of(npc -> {
            if (calls.incrementAndGet() == 1) template[0].apply(npc);
        }));
        assertThrows(IllegalStateException.class, () -> template[0].apply(target.npc));
        assertEquals(1, calls.get()); assertTrue(target.spawned);
        assertDoesNotThrow(() -> template[0].apply(target.npc));
        assertEquals(2, calls.get());
    }

    @Test void nestedApplicationToAnotherNpcRemainsAllowed() {
        UUID uuid = UUID.randomUUID();
        var first = new Target(uuid); var second = new Target(uuid); var calls = new java.util.concurrent.atomic.AtomicInteger();
        Template[] template = new Template[1];
        template[0] = new Template(ResourceLocation.fromNamespaceAndPath("test", "nested"), java.util.List.of(npc -> {
            calls.incrementAndGet();
            if (npc == first.npc) template[0].apply(second.npc);
        }));
        template[0].apply(first.npc);
        assertEquals(2, calls.get()); assertTrue(first.spawned); assertTrue(second.spawned);
    }

    @Test void anotherInstanceOfTheSameTemplateCannotReenterAndCanRetryAfterward() {
        var target = new Target(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var key = ResourceLocation.fromNamespaceAndPath("test", "replacement");
        var replacement = new Template(key, java.util.List.of(npc -> calls.incrementAndGet()));
        var original = new Template(key, java.util.List.of(replacement::apply));
        assertThrows(IllegalStateException.class, () -> original.apply(target.npc));
        assertEquals(0, calls.get()); assertTrue(target.spawned);
        replacement.apply(target.npc);
        assertEquals(1, calls.get());
    }

    @Test void differentTemplateKeysCanNestOnTheSameNpc() {
        var target = new Target(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var nested = new Template(ResourceLocation.fromNamespaceAndPath("test", "nested_key"),
                java.util.List.of(npc -> calls.incrementAndGet()));
        var outer = new Template(ResourceLocation.fromNamespaceAndPath("test", "outer_key"),
                java.util.List.of(nested::apply, npc -> calls.incrementAndGet()));
        outer.apply(target.npc);
        assertEquals(2, calls.get()); assertTrue(target.spawned);
    }

    @Test void replacementInstanceCannotReenterDuringRespawnRecovery() {
        var target = new Target(); var original = template(); var replacement = template();
        target.afterSpawn = () -> replacement.apply(target.npc);
        assertThrows(IllegalStateException.class, () -> original.apply(target.npc));
        assertEquals(1, target.saves);
        target.afterSpawn = () -> {};
        replacement.apply(target.npc);
        assertEquals(2, target.saves);
    }

    @Test void reentryDuringRespawnIsGuardedAndFailureStillReleasesTheGuard() {
        var target = new Target(); var template = template();
        target.afterSpawn = () -> template.apply(target.npc);
        assertThrows(IllegalStateException.class, () -> template.apply(target.npc));
        target.afterSpawn = () -> {};
        assertDoesNotThrow(() -> template.apply(target.npc));
        assertEquals(2, target.saves);
    }

    @Test void rejectedDespawnDoesNotApplyActions() {
        var target = new Target(); target.allowDespawn = false;
        assertThrows(IllegalStateException.class, () -> template().apply(target.npc));
        assertEquals(0, target.saves); assertEquals(0, target.spawns); assertTrue(target.spawned);
    }

    @Test void actionFailureRestoresSpawnAndPreservesTheOriginalError() {
        var target = new Target(); target.saveFailure = new IllegalStateException("action");
        assertSame(target.saveFailure, assertThrows(IllegalStateException.class, () -> template().apply(target.npc)));
        assertTrue(target.spawned); assertEquals(1, target.spawns);
    }

    @Test void recoveryFailureIsSuppressedOnTheActionFailure() {
        var target = new Target(); target.saveFailure = new IllegalStateException("action");
        target.spawnFailure = new IllegalStateException("recovery");
        var failure = assertThrows(IllegalStateException.class, () -> template().apply(target.npc));
        assertSame(target.saveFailure, failure);
        assertArrayEquals(new Throwable[]{target.spawnFailure}, failure.getSuppressed());
    }

    @Test void unsuccessfulRespawnCannotBeReportedAsSuccessfulApplication() {
        var target = new Target(); target.allowSpawn = false;
        assertThrows(IllegalStateException.class, () -> template().apply(target.npc));
        assertEquals(1, target.saves); assertEquals(1, target.spawns);
    }

    @Test void unregisteredInstanceIsNotRespawned() {
        var target = new Target(); target.registered = false;
        template().apply(target.npc);
        assertEquals(0, target.spawns);
        assertEquals(0, target.saves);
        assertTrue(target.spawned);
    }

    @Test void lostRegistrationStopsTheRemainingActions() {
        var target = new Target(); var laterCalls = new java.util.concurrent.atomic.AtomicInteger();
        var action = new Template(ResourceLocation.fromNamespaceAndPath("test", "ownership"), java.util.List.of(
                npc -> target.registered = false, npc -> laterCalls.incrementAndGet()));
        action.apply(target.npc);
        assertEquals(0, laterCalls.get()); assertEquals(0, target.spawns);
    }

    @Test void losingOnlyUuidOwnershipAlsoStopsTheRemainingActions() {
        var target = new Target(); var laterCalls = new java.util.concurrent.atomic.AtomicInteger();
        var action = new Template(ResourceLocation.fromNamespaceAndPath("test", "uuid"), java.util.List.of(
                npc -> target.ownsUuid = false, npc -> laterCalls.incrementAndGet()));
        action.apply(target.npc);
        assertEquals(0, laterCalls.get()); assertEquals(0, target.spawns);
    }
}
