package net.citizensnpcs.api.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The upstream descriptor remains the source of permission defaults and declared child relationships. */
final class PermissionDefaults {
    private final Map<String, Default> defaults;
    private final Map<String, List<Parent>> parents = new LinkedHashMap<>();

    private PermissionDefaults(Map<String, Default> defaults, Map<String, Map<String, Boolean>> directParents) {
        this.defaults = Map.copyOf(defaults);
        for (String permission : defaults.keySet()) {
            Map<String, Boolean> inherited = new LinkedHashMap<>();
            collectParents(permission, true, directParents, inherited, new HashSet<>());
            List<Parent> ordered = new ArrayList<>();
            inherited.forEach((name, value) -> ordered.add(new Parent(name, value)));
            // A narrower declared parent takes precedence over a broader one. Direct backend decisions take
            // precedence over this entire fallback, including an explicit denial on the requested permission.
            ordered.sort(Comparator.comparingInt((Parent parent) -> parent.permission().length()).reversed()
                    .thenComparing(Parent::permission));
            parents.put(permission, List.copyOf(ordered));
        }
    }

    static PermissionDefaults bundled() {
        try (InputStream input = PermissionDefaults.class.getResourceAsStream("/citizens/permissions.yml")) {
            if (input == null) throw new IllegalStateException("Missing Citizens permission descriptor");
            return load(input);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read Citizens permission descriptor", ex);
        }
    }

    static PermissionDefaults load(InputStream input) {
        Map<?, ?> document = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        if (document == null || !(document.get("permissions") instanceof Map<?, ?> entries))
            throw new IllegalArgumentException("Permission descriptor must contain a permissions map");
        Map<String, Default> defaults = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> parents = new LinkedHashMap<>();
        for (var entry : entries.entrySet()) {
            String name = normalize(entry.getKey().toString());
            if (!(entry.getValue() instanceof Map<?, ?> definition))
                throw new IllegalArgumentException("Invalid permission definition: " + name);
            // Bukkit looks up the literal 'default' key and otherwise uses OP. In particular, the upstream
            // generator's compact {default:false} is a different YAML key, not an explicit false default.
            defaults.put(name, Default.parse(definition.get("default")));
            if (definition.get("children") instanceof Map<?, ?> children) {
                for (var child : children.entrySet()) {
                    if (!(child.getValue() instanceof Boolean value))
                        throw new IllegalArgumentException("Invalid permission child: " + child.getKey());
                    String childName = normalize(child.getKey().toString());
                    parents.computeIfAbsent(childName, key -> new LinkedHashMap<>()).put(name, value);
                }
            }
        }
        parents.keySet().forEach(name -> defaults.putIfAbsent(name, Default.OP));
        return new PermissionDefaults(defaults, parents);
    }

    private static void collectParents(String permission, boolean value, Map<String, Map<String, Boolean>> direct,
            Map<String, Boolean> result, Set<String> path) {
        if (!path.add(permission)) throw new IllegalArgumentException("Cyclic permission children: " + permission);
        for (var parent : direct.getOrDefault(permission, Map.of()).entrySet()) {
            boolean inherited = value == parent.getValue();
            result.putIfAbsent(parent.getKey(), inherited);
            collectParents(parent.getKey(), inherited, direct, result, path);
        }
        path.remove(permission);
    }

    Collection<String> permissions() {
        return defaults.keySet();
    }

    List<Parent> parents(String permission) {
        return parents.getOrDefault(permission, List.of());
    }

    boolean fallback(String permission, boolean operator) {
        for (Parent parent : parents(permission)) {
            if (defaults.getOrDefault(parent.permission(), Default.OP).applies(operator)) return parent.value();
        }
        return defaults.getOrDefault(permission, Default.OP).applies(operator);
    }

    static String normalize(String permission) {
        return permission.trim().toLowerCase(Locale.ROOT);
    }

    record Parent(String permission, boolean value) {
        boolean inherit(boolean granted) {
            return granted == value;
        }
    }

    private enum Default {
        TRUE, FALSE, OP, NOT_OP;

        static Default parse(Object value) {
            if (value == null) return OP;
            return switch (value.toString().toLowerCase(Locale.ROOT).replace(" ", "")) {
                case "true" -> TRUE;
                case "false" -> FALSE;
                case "op", "isop", "operator", "isoperator", "admin", "isadmin" -> OP;
                case "!op", "notop", "!operator", "notoperator", "!admin", "notadmin" -> NOT_OP;
                default -> throw new IllegalArgumentException("Invalid permission default: " + value);
            };
        }

        boolean applies(boolean operator) {
            return this == TRUE || this == OP && operator || this == NOT_OP && !operator;
        }
    }
}
