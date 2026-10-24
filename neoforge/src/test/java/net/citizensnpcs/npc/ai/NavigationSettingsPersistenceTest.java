package net.citizensnpcs.npc.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.citizensnpcs.api.util.MemoryDataKey;

class NavigationSettingsPersistenceTest {
    private static CitizensNavigator navigator() {
        var metadata = new SimpleMetadataStore();
        NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("data")) return metadata;
                    throw new AssertionError(method.getName());
                });
        return new CitizensNavigator(npc);
    }

    @Test void editedAttackAndPathParametersSurviveANewNavigator() {
        var original = navigator();
        original.getDefaultParameters().attackRange(7.25).attackDelayTicks(63).pathDistanceMargin(0.125);
        var data = new MemoryDataKey(); original.save(data);
        var restored = navigator(); restored.load(data);
        assertEquals(7.25, restored.getDefaultParameters().attackRange());
        assertEquals(63, restored.getDefaultParameters().attackDelayTicks());
        assertEquals(0.125, restored.getDefaultParameters().pathDistanceMargin());
    }

    @Test void explicitZeroMarginsAndSignedAttackDelaysLoadWithoutDefaultSubstitution() {
        var data = new MemoryDataKey();
        data.setDouble("attackrange", 0); data.setInt("attackdelayticks", -2); data.setDouble("pathdistancemargin", 0);
        var restored = navigator(); restored.load(data);
        assertEquals(0, restored.getDefaultParameters().attackRange());
        assertEquals(-2, restored.getDefaultParameters().attackDelayTicks());
        assertEquals(0, restored.getDefaultParameters().pathDistanceMargin());
    }

    @Test void revertingToDefaultsClearsPreviouslySavedOverridesWithoutTouchingSiblings() {
        var data = new MemoryDataKey();
        data.setDouble("attackrange", 7.25); data.setInt("attackdelayticks", 63); data.setDouble("pathdistancemargin", 0.125);
        data.setString("extension-owned", "retained");
        navigator().save(data);
        assertFalse(data.keyExists("attackrange"));
        assertFalse(data.keyExists("attackdelayticks"));
        assertFalse(data.keyExists("pathdistancemargin"));
        assertEquals("retained", data.getString("extension-owned"));
    }

    @Test void legacySnapshotsWithoutNewKeysRetainConfiguredDefaults() {
        var restored = navigator(); restored.load(new MemoryDataKey());
        assertEquals(Setting.NPC_ATTACK_DISTANCE.asDouble(), restored.getDefaultParameters().attackRange());
        assertEquals(new NavigatorParameters().attackDelayTicks(), restored.getDefaultParameters().attackDelayTicks());
        assertEquals(Setting.DEFAULT_PATH_DISTANCE_MARGIN.asDouble(), restored.getDefaultParameters().pathDistanceMargin());
    }

    @Test void loadingADefaultSnapshotClearsEarlierLiveOverrides() {
        var defaults = new MemoryDataKey(); navigator().save(defaults);
        var changed = navigator();
        changed.getDefaultParameters().attackRange(7.25).attackDelayTicks(63).pathDistanceMargin(0.125);
        changed.load(defaults);
        assertEquals(Setting.NPC_ATTACK_DISTANCE.asDouble(), changed.getDefaultParameters().attackRange());
        assertEquals(new NavigatorParameters().attackDelayTicks(), changed.getDefaultParameters().attackDelayTicks());
        assertEquals(Setting.DEFAULT_PATH_DISTANCE_MARGIN.asDouble(), changed.getDefaultParameters().pathDistanceMargin());
    }
}
