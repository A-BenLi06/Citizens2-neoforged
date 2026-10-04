package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.util.PermissionUtil;
import net.citizensnpcs.api.util.PermissionUtil.GroupResolver;

class LuckPermsGroupsTest {
    private GroupResolver previous;

    @BeforeEach
    void preserveResolver() {
        previous = PermissionUtil.getGroupResolver();
        LuckPermsGroups.uninstall();
    }

    @AfterEach
    void restoreResolver() {
        LuckPermsGroups.uninstall();
        PermissionUtil.setGroupResolver(previous);
    }

    @Test
    void uninstallReleasesItsOwnResolverAndServerReferences() throws Exception {
        GroupResolver owned = (player, group) -> true;
        field("installed").set(null, owned);
        field("api").set(null, new Object());
        Method fixtureMethod = Object.class.getMethod("toString");
        for (Field candidate : LuckPermsGroups.class.getDeclaredFields()) {
            if (candidate.getType() == Method.class) {
                candidate.setAccessible(true);
                candidate.set(null, fixtureMethod);
            }
        }
        @SuppressWarnings("unchecked")
        Set<UUID> warned = (Set<UUID>) field("WARNED").get(null);
        warned.add(UUID.randomUUID());
        PermissionUtil.setGroupResolver(owned);

        LuckPermsGroups.uninstall();

        assertNull(PermissionUtil.getGroupResolver());
        assertNull(field("installed").get(null));
        assertNull(field("api").get(null));
        assertTrue(warned.isEmpty());
        for (Field candidate : LuckPermsGroups.class.getDeclaredFields()) {
            if (candidate.getType() == Method.class) {
                candidate.setAccessible(true);
                assertNull(candidate.get(null), candidate.getName());
            }
        }
        LuckPermsGroups.uninstall();
        assertNull(PermissionUtil.getGroupResolver());
    }

    @Test
    void uninstallPreservesReplacementProvider() throws Exception {
        GroupResolver owned = (player, group) -> true;
        GroupResolver replacement = (player, group) -> false;
        field("installed").set(null, owned);
        PermissionUtil.setGroupResolver(replacement);

        LuckPermsGroups.uninstall();

        assertSame(replacement, PermissionUtil.getGroupResolver());
        assertNull(field("installed").get(null));
        LuckPermsGroups.uninstall();
        assertSame(replacement, PermissionUtil.getGroupResolver());
    }

    private static Field field(String name) throws Exception {
        Field field = LuckPermsGroups.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
