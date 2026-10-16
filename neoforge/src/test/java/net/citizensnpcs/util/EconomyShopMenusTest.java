package net.citizensnpcs.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class EconomyShopMenusTest {
    @Test void preservesOpaqueIdentifiersAndUnicodeText() {
        var target = EconomyShopMenus.parse(List.of("PAGE", "A shop", "资源.items.special"));
        assertEquals("page", target.kind());
        assertEquals(List.of("A shop", "资源.items.special"), target.fields());
        assertThrows(UnsupportedOperationException.class, () -> target.fields().clear());
        assertEquals(" 资源 ", EconomyShopMenus.parse(List.of("search", " 资源 ", "0")).fields().getFirst());
    }

    @Test void rejectsMalformedFieldsBeforeProviderAccess() {
        for (var fields : List.of(List.<String>of(), List.of("unknown", "0"), List.of("browser", "-1"),
                List.of("browser", "2147483648"), List.of("browser", "1.5"), List.of("page", "blocks"),
                List.of("shop", " "), List.of("quantity", "blocks", "offer", "transfer"),
                List.of("search", "query", "0", "extra")))
            assertThrows(IllegalArgumentException.class, () -> EconomyShopMenus.parse(fields), fields.toString());
    }
}
