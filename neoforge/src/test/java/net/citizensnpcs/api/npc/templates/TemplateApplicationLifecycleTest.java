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
        int saves, spawns;
        RuntimeException saveFailure, spawnFailure;
        NPC npc;
        Target() {
            UUID uuid = UUID.randomUUID();
            NPCRegistry registry = (NPCRegistry) Proxy.newProxyInstance(NPCRegistry.class.getClassLoader(), new Class<?>[]{NPCRegistry.class},
                    (proxy, method, args) -> registered ? npc : null);
            npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isSpawned": return spawned;
                    case "getOwningRegistry": return registry;
                    case "getId": return 1;
                    case "getUniqueId": return uuid;
                    case "getStoredLocation": return null;
                    case "despawn": if (allowDespawn) spawned = false; return allowDespawn;
                    case "spawn": spawns++; if (spawnFailure != null) throw spawnFailure; spawned = allowSpawn; return allowSpawn;
                    case "saveSnapshot": saves++; if (saveFailure != null) throw saveFailure; return null;
                    case "load": return null;
                    default: throw new AssertionError(method.getName());
                }
            });
        }
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
    }
}
