package net.citizensnpcs.api.util;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Citizens entity filter clauses, backed by native entity types and the installed permission/group services. */
public final class EntityFilters {
    private EntityFilters() { }

    /** Space-separated clauses are AND-ed; types/groups are alternatives, permission lists require every grant. */
    public static Predicate<Entity> parse(String raw) {
        Predicate<Entity> result = entity -> true;
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("none")) return result;
        for (String clause : raw.trim().split("\\s+")) {
            String[] parts = clause.split("=", -1);
            if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException("Invalid filter clause: " + clause);
            List<String> values = Arrays.asList(parts[1].split(",", -1));
            if (values.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Empty filter value: " + clause);
            Predicate<Entity> predicate = switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "type" -> {
                    Set<EntityType<?>> types = values.stream().map(value -> {
                        ResourceLocation id = ResourceLocation.tryParse(value.toLowerCase(Locale.ROOT));
                        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                    }).collect(Collectors.toSet());
                    if (types.contains(null)) throw new IllegalArgumentException("Unknown entity type: " + clause);
                    yield entity -> types.contains(entity.getType());
                }
                case "permission", "perm" -> entity -> entity instanceof ServerPlayer player
                        && values.stream().allMatch(value -> PermissionUtil.hasPermission(player, value));
                case "group" -> entity -> entity instanceof ServerPlayer player
                        && Boolean.TRUE.equals(PermissionUtil.inGroup(values, player));
                default -> throw new IllegalArgumentException("Unknown filter clause: " + parts[0]);
            };
            result = result.and(predicate);
        }
        return result;
    }
}
