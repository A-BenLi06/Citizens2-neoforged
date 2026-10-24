package net.citizensnpcs.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.TeleportStuckAction;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.SimpleMetadataStore;

class NavigationCommandValidationTest {
    private final NPCCommands commands = new NPCCommands();

    private static class Target {
        final NavigatorParameters parameters = new NavigatorParameters().range(32).stationaryTicks(15)
                .destinationTeleportMargin(3).distanceMargin(2).pathDistanceMargin(1).attackRange(2)
                .stuckAction(TeleportStuckAction.INSTANCE);
        final SimpleMetadataStore metadata = new SimpleMetadataStore();
        final Navigator navigator = (Navigator) Proxy.newProxyInstance(Navigator.class.getClassLoader(),
                new Class<?>[]{Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDefaultParameters")) return parameters;
                    throw new AssertionError(method.getName());
                });
        final NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getNavigator" -> navigator;
                    case "data" -> metadata;
                    case "getName", "getRawName" -> "NavigationTest";
                    default -> throw new AssertionError(method.getName());
                });
        void unchanged() {
            assertFalse(parameters.avoidWater());
            assertSame(TeleportStuckAction.INSTANCE, parameters.stuckAction());
            assertEquals(32, parameters.range()); assertEquals(15, parameters.stationaryTicks());
            assertEquals(3, parameters.destinationTeleportMargin()); assertEquals(2, parameters.distanceMargin());
            assertEquals(1, parameters.pathDistanceMargin()); assertEquals(2, parameters.attackRange());
            assertEquals(20, parameters.attackDelayTicks()); assertTrue(metadata.getKeys().isEmpty());
        }
    }

    @Test void invalidLateOptionCannotApplyEarlierNavigationOrMetadataChanges() {
        var target = new Target();
        assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, target.npc,
                64F, true, true, true, 40, 5D, 4D, Duration.ofSeconds(1), 3D, -1D, null, null));
        target.unchanged();
    }

    @Test void invalidStationaryTimeCannotDisableTeleportingOrChangeWaterPolicy() {
        var target = new Target();
        assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, target.npc,
                null, true, true, true, -1, null, null, null, null, null, null, null));
        target.unchanged();
    }

    @Test void durationOverflowIsACommandRejectionBeforeAnyMutation() {
        var target = new Target();
        assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, target.npc,
                null, true, true, true, null, null, null, Duration.ofSeconds(Long.MAX_VALUE), null, null, null, null));
        target.unchanged();
    }

    @Test void directCallsRejectNonFinitePathValuesBeforeAccessingTheNpc() {
        for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, null,
                    (float) invalid, null, null, null, null, null, null, null, null, null, null, null));
            assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, null,
                    null, null, null, null, null, invalid, null, null, null, null, null, null));
            assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, null,
                    null, null, null, null, null, null, invalid, null, null, null, null, null));
            assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, null,
                    null, null, null, null, null, null, null, null, invalid, null, null, null));
            assertThrows(CommandException.class, () -> commands.pathfindingOptions(null, null, null,
                    null, null, null, null, null, null, null, null, null, invalid, null, null));
        }
    }

    @Test void speedRejectsNonFiniteOrFloatOverflowWithoutChangingTheModifier() {
        for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e39", "-1e39")) {
            var target = new Target();
            var context = new CommandContext(null, new String[]{"npc", "speed", '"' + invalid + '"'});
            assertThrows(CommandException.class, () -> commands.speed(context, null, target.npc), invalid);
            assertEquals(1F, target.parameters.speedModifier());
        }
    }

    @Test void nonFiniteVelocityIsRejectedBeforeAccessingEntityState() {
        for (double invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(CommandException.class, () -> commands.velocity(null, null, null, invalid, 0, 0));
            assertThrows(CommandException.class, () -> commands.velocity(null, null, null, 0, invalid, 0));
            assertThrows(CommandException.class, () -> commands.velocity(null, null, null, 0, 0, invalid));
        }
    }
}
