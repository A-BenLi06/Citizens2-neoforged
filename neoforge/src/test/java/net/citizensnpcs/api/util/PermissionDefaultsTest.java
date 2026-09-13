package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PermissionDefaultsTest {
    private static PermissionDefaults read(String yaml) {
        return PermissionDefaults.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void readsBukkitDefaultsWithoutReinterpretingMalformedInlineKeys() {
        var definitions = read("""
                permissions:
                  test.public: {default: true}
                  test.private: {default: false}
                  test.operator: {default: op}
                  test.visitor: {default: not op}
                  test.implicit: {}
                  test.compact: {default:false}
                """);
        assertTrue(definitions.fallback("test.public", false));
        assertFalse(definitions.fallback("test.private", true));
        assertFalse(definitions.fallback("test.operator", false));
        assertTrue(definitions.fallback("test.operator", true));
        assertTrue(definitions.fallback("test.visitor", false));
        assertFalse(definitions.fallback("test.visitor", true));
        assertTrue(definitions.fallback("test.implicit", true));
        assertTrue(definitions.fallback("test.compact", true));
    }

    @Test void declaredParentsIncludeTheirBasePermissionAndNestedChildren() {
        var definitions = read("""
                permissions:
                  test.root.*:
                    default: false
                    children:
                      test.root: true
                      test.nested: true
                  test.nested:
                    default: false
                    children:
                      test.leaf: false
                """);
        assertTrue(definitions.parents("test.root").contains(new PermissionDefaults.Parent("test.root.*", true)));
        assertTrue(definitions.parents("test.leaf").contains(new PermissionDefaults.Parent("test.root.*", false)));
        assertFalse(definitions.parents("test.leaf").getFirst().inherit(true));
        assertTrue(definitions.parents("test.leaf").getFirst().inherit(false));
        assertFalse(definitions.fallback("test.nested", false));
    }

    @Test void activeParentDefaultsApplyToChildrenWithoutInventingAStringHierarchy() {
        var definitions = read("""
                permissions:
                  test.family.*:
                    default: true
                    children:
                      test.family: true
                      unrelated.child: true
                  test.family: {default: false}
                  unrelated.child: {default: false}
                """);
        assertTrue(definitions.fallback("test.family", false));
        assertTrue(definitions.fallback("unrelated.child", false));
        assertFalse(definitions.fallback("test.family.undeclared", false));
    }

    @Test void bundledDescriptorRetainsOriginalParentRelationships() {
        var definitions = PermissionDefaults.bundled();
        assertTrue(definitions.permissions().contains("citizens.npc.create"));
        assertTrue(definitions.parents("citizens.npc.tphere")
                .contains(new PermissionDefaults.Parent("citizens.npc.tphere.*", true)));
        assertTrue(definitions.parents("citizens.npc.showshop")
                .contains(new PermissionDefaults.Parent("citizens.npc.showshop.*", true)));
        assertFalse(definitions.fallback("citizens.admin", false));
        assertTrue(definitions.fallback("citizens.admin", true));
    }

    @Test void cyclesAndInvalidDefaultsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> read("""
                permissions:
                  test.first: {children: {test.second: true}}
                  test.second: {children: {test.first: true}}
                """));
        assertThrows(IllegalArgumentException.class, () -> read("permissions: {test.node: {default: perhaps}}"));
    }
}
