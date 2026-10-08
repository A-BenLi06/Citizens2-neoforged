package net.citizensnpcs.api.ai.tree;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.expr.ExpressionRegistry;
import net.citizensnpcs.api.expr.ExpressionScope;
import net.citizensnpcs.api.expr.Memory;
import net.citizensnpcs.api.util.MemoryDataKey;

class BehaviorContextTest {
    @Test void scalarValuesFillOnlyTheFirstPositionalParameter() {
        var root = new MemoryDataKey(java.util.Map.of("node", "original"));
        var params = root.getRelative("node"); var context = context();
        assertEquals("original", context.getArgOrParam(0, "key", params, "fallback"));
        assertEquals("default-duration", context.getArgOrParam(1, "duration", params, "default-duration"));
        assertEquals(java.util.Map.of("node", "original"), root.getValuesDeep());
        context.setArgs(new String[]{"override"});
        assertEquals("override", context.getArgOrParam(0, "key", params, "fallback"));
    }

    @Test void scalarArgumentMapsDoNotInventAdditionalPositions() {
        var root = new MemoryDataKey(java.util.Map.of("node", 12));
        var context = context();
        assertEquals(java.util.Map.of("0", "12"), context.parseArgs(3, root.getRelative("node")));
        assertTrue(context.parseArgs(0, root.getRelative("node")).isEmpty());
        assertEquals(java.util.Map.of("node", 12), root.getValuesDeep());
    }
    @Test void registrationAndLookupUseTheSameLocaleIndependentNames() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            var registry = new BehaviorRegistry(new ExpressionRegistry());
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            registry.registerBehavior("SIGNAL_ALIAS", (params, context) -> (InstantBehavior) () -> {
                calls.incrementAndGet(); return BehaviorStatus.SUCCESS;
            });
            assertTrue(registry.hasBehavior("signal_alias"));
            assertEquals(BehaviorStatus.SUCCESS, registry.createBehavior("SIGNAL_ALIAS", null, context()).run());
            assertEquals(1, calls.get());
        } finally { java.util.Locale.setDefault(previous); }
    }

    @Test void namedOptionsNormalizeKeysWithoutChangingValues() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            var context = context();
            context.setArgs(new String[]{"OriginalPosition", "DISTANCE_MARGIN=KeepCase", "PRIORITY=I"});
            var parsed = context.parseArgs(1, null);
            assertEquals("OriginalPosition", parsed.get("0"));
            assertEquals("KeepCase", parsed.get("distance_margin"));
            assertEquals("I", parsed.get("priority"));
        } finally { java.util.Locale.setDefault(previous); }
    }
    private static BehaviorRegistry.BehaviorContext context() {
        return new BehaviorRegistry.BehaviorContext(null, new ExpressionScope(), new ExpressionRegistry(), new Memory());
    }

    @Test void scalarParametersAreReadWithoutBeingReplacedByDefaults() {
        var root = new MemoryDataKey(); root.setString("signal", "KeepCase");
        var before = root.copy().getValuesDeep();
        assertEquals("KeepCase", context().getArgOrParam(0, "", root.getRelative("signal"), "fallback"));
        assertEquals(before, root.getValuesDeep());
    }

    @Test void missingParameterDefaultsNeverCreateConfigurationEntries() {
        var root = new MemoryDataKey(); var before = root.copy().getValuesDeep();
        var context = context();
        assertEquals("1s", context.getArgOrParam(0, "duration", root, "1s"));
        assertNull(context.getArgOrParam(0, "optional", root, null));
        assertEquals(before, root.getValuesDeep());
        assertEquals("fallback", context.getArgOrParam(0, "value", null, "fallback"));
    }

    @Test void explicitArgumentsIncludingEmptyStringsKeepPriority() {
        var context = context(); context.setArgs(new String[]{""});
        var root = new MemoryDataKey(); root.setString("value", "configured");
        assertEquals("", context.getArgOrParam(0, "value", root, "fallback"));
        assertEquals("configured", context.getArgOrParam(1, "value", root, "fallback"));
        assertEquals("configured", root.getString("value"));
    }
}
