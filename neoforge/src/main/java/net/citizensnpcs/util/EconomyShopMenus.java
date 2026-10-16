package net.citizensnpcs.util;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;

/** Optional presentation adapter. Economy owns menu validation, contents and every trade. */
public final class EconomyShopMenus {
    private static final String API = "net.yuuniverse.economy.api.";

    private EconomyShopMenus() { }

    public record Target(String kind, List<String> fields) {
        public Target { fields = List.copyOf(fields); }
    }

    /** IDs/text remain literal. Only target keywords and trade direction are normalized. */
    public static Target parse(List<String> fields) {
        if (fields.isEmpty()) throw new IllegalArgumentException("Missing Economy menu target");
        String kind = fields.getFirst().toLowerCase(Locale.ROOT);
        int count = switch (kind) {
            case "browser", "shop", "coordinate" -> 2;
            case "page", "search" -> 3;
            case "quantity" -> 4;
            default -> throw new IllegalArgumentException("Unknown Economy menu target: " + kind);
        };
        if (fields.size() != count) throw new IllegalArgumentException("Invalid Economy menu target fields");
        List<String> arguments = List.copyOf(fields.subList(1, fields.size()));
        if (arguments.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Blank Economy menu field");
        if (kind.equals("browser") || kind.equals("search")) {
            int page = Integer.parseInt(arguments.getLast());
            if (page < 0) throw new IllegalArgumentException("Negative Economy menu page");
        }
        if (kind.equals("quantity") && !List.of("buy", "sell").contains(arguments.getLast().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("Economy quantity direction must be buy or sell");
        return new Target(kind, arguments);
    }

    /** Acquires the current provider on every preflight/execution; retains no retired facade. */
    public static void present(ServerPlayer player, Target target, boolean validate) {
        if (player == null) throw new IllegalArgumentException("Economy menus require an online player");
        if (player.getServer() == null || !player.getServer().isSameThread())
            throw new IllegalStateException("Economy menus require the server thread");
        if (player.hasDisconnected() || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player)
            throw new IllegalStateException("Economy menu recipient is no longer the active player session");
        try {
            Class<?> apiType = Class.forName(API + "EconomyApi");
            Object service = ((Optional<?>) Class.forName(API + "YuuniverseEconomyApi")
                    .getMethod("current").invoke(null)).orElseThrow(
                            () -> new IllegalStateException("Yuuniverse Economy is unavailable"));
            if (target.kind().equals("coordinate")) {
                Object coordinates = ((Optional<?>) apiType.getMethod("coordinateShops").invoke(service))
                        .orElseThrow(() -> new IllegalStateException("The Economy coordinate-shop API is unavailable"));
                Class<?> reference = Class.forName(API + "CoordinateShopReference");
                Object id = Class.forName(API + "CoordinateShopReference$Id").getConstructor(String.class)
                        .newInstance(target.fields().getFirst());
                Class.forName(API + "CoordinateShopApi").getMethod(validate ? "validate" : "open", UUID.class, reference)
                        .invoke(coordinates, player.getUUID(), id);
                return;
            }
            Object shops;
            try {
                shops = ((Optional<?>) apiType.getMethod("systemShops").invoke(service)).orElse(null);
            } catch (NoSuchMethodException olderProvider) {
                shops = null;
            }
            if (shops == null) {
                if (!target.kind().equals("shop"))
                    throw new IllegalStateException("The Economy provider does not support this menu target");
                apiType.getMethod(validate ? "validateSystemShop" : "openSystemShop", UUID.class, String.class)
                        .invoke(service, player.getUUID(), target.fields().getFirst());
                return;
            }
            Object request = request(target);
            Class.forName(API + "SystemShopApi")
                    .getMethod(validate ? "validate" : "open", UUID.class, Class.forName(API + "ShopMenuRequest"))
                    .invoke(shops, player.getUUID(), request);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("Economy menu request failed", failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("The Yuuniverse Economy menu API is unavailable", failure);
        }
    }

    private static Object request(Target target) throws ReflectiveOperationException {
        var fields = target.fields();
        return switch (target.kind()) {
            case "browser" -> Class.forName(API + "ShopMenuRequest$Browser").getConstructor(int.class)
                    .newInstance(Integer.parseInt(fields.getFirst()));
            case "shop" -> Class.forName(API + "ShopMenuRequest$Shop").getConstructor(String.class)
                    .newInstance(fields.getFirst());
            case "page" -> Class.forName(API + "ShopMenuRequest$Page").getConstructor(String.class, String.class)
                    .newInstance(fields.getFirst(), fields.getLast());
            case "search" -> Class.forName(API + "ShopMenuRequest$Search").getConstructor(String.class, int.class)
                    .newInstance(fields.getFirst(), Integer.parseInt(fields.getLast()));
            case "quantity" -> {
                Class<?> action = Class.forName("net.yuuniverse.economy.core.CommerceAction");
                Object direction = action.getField(fields.getLast().equalsIgnoreCase("buy")
                        ? "BUY_FROM_SHOP" : "SELL_TO_SHOP").get(null);
                yield Class.forName(API + "ShopMenuRequest$Quantity").getConstructor(String.class, String.class, action)
                        .newInstance(fields.get(0), fields.get(1), direction);
            }
            default -> throw new IllegalArgumentException("Unknown Economy menu target: " + target.kind());
        };
    }
}
