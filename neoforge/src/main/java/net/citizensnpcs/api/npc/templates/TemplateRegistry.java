package net.citizensnpcs.api.npc.templates;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.common.base.Joiner;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Storage;
import net.citizensnpcs.api.util.YamlStorage;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Loads every {@code templates/<namespace>/<file>.yml} under the data folder and hands out the templates by key or name.
 * <p>
 * Keys are {@link ResourceLocation}s in place of Bukkit's {@code NamespacedKey}; the namespace is the folder name and the
 * path is the template's own key in the file. Because a {@code ResourceLocation} rejects capitals and most punctuation
 * where {@code NamespacedKey} silently lowercased, a template name that cannot be a valid path is reported as a load
 * error naming itself rather than throwing out of the walk.
 * <p>
 * Loose {@code *.yml} files directly in {@code templates/} are the pre-namespace layout; they are migrated into
 * {@code templates/migrated/} on first load and the original is renamed to {@code .migrated}, exactly as upstream does.
 */
public class TemplateRegistry {
    private final Path baseFolder;
    private final boolean strict;
    private final Map<ResourceLocation, Template> fullyQualifiedTemplates = new HashMap<>();
    private final Multimap<String, Template> templatesByName = HashMultimap.create();
    private final Map<ResourceLocation, File> templateSources = new HashMap<>();
    private final Map<ResourceLocation, String> templateSourceKeys = new HashMap<>();

    public TemplateRegistry(Path folder) {
        this(folder, false);
    }

    /** Decode a complete replacement; callers publish it only after the rest of reload is ready. */
    public static TemplateRegistry prepareReload(Path folder) {
        return new TemplateRegistry(folder, true);
    }

    private TemplateRegistry(Path folder, boolean strict) {
        this.strict = strict;
        this.baseFolder = folder;
        if (strict) {
            try { Files.createDirectories(folder); }
            catch (IOException failure) { throw new IllegalStateException("Unable to open template folder " + folder, failure); }
        } else folder.toFile().mkdirs();
        loadTemplates(baseFolder);
    }

    /** Validates and saves one NPC snapshot template, then publishes it through both lookup indexes. */
    public void generateTemplateFromNPC(ResourceLocation key, NPC npc) {
        if (key.getPath().isEmpty()) throw new IllegalArgumentException("Template name must not be empty");
        File namespaceFolder = new File(baseFolder.toFile(), key.getNamespace());
        File generatedFile = templateSources.getOrDefault(key, new File(namespaceFolder, "templates.yml"));
        generatedFile.getParentFile().mkdirs();
        String storedKey = templateSourceKeys.getOrDefault(key, key.getPath());
        Storage templateStorage = new YamlStorage(generatedFile);
        if (!templateStorage.load())
            throw new IllegalStateException("could not load " + generatedFile);

        Map<String, Object> entries = new java.util.LinkedHashMap<>(templateStorage.getKey("").getValuesDeep());
        DataKey candidate = new MemoryDataKey();
        Object existing = entries.get(storedKey);
        if (existing != null) candidate.setRaw("", existing);
        DataKey root = candidate.getRelative("yaml_replace");
        root.removeKey("replacements");
        npc.saveSnapshot(root.getRelative("replacements"));
        root.setBoolean("override", true);
        // the uuid is the one thing that must not be copied: two NPCs sharing it would collide in the registry
        root.removeKey("replacements.uuid");
        Template replacement;
        try {
            replacement = decodeTemplate(generatedFile.getParentFile(), key, candidate);
        } catch (TemplateLoadException ex) {
            throw new IllegalStateException("Unable to validate generated template " + key, ex);
        }
        entries.put(storedKey, candidate.getRaw(""));
        templateStorage.getKey("").setRaw("", entries);
        templateStorage.save();
        Template previous = fullyQualifiedTemplates.put(key, replacement);
        if (previous != null) templatesByName.remove(key.getPath(), previous);
        templatesByName.put(key.getPath(), replacement);
        templateSources.put(key, generatedFile);
        templateSourceKeys.put(key, storedKey);
    }

    /** @return an immutable snapshot ordered by fully qualified template key */
    public Collection<Template> getAllTemplates() {
        return orderedTemplates(fullyQualifiedTemplates.values());
    }

    public Template getTemplateByKey(ResourceLocation key) {
        return fullyQualifiedTemplates.get(key);
    }

    /** @return an immutable snapshot of every template with this bare name, across all namespaces */
    public Collection<Template> getTemplates(String name) {
        return orderedTemplates(templatesByName.get(name.toLowerCase(Locale.ROOT)));
    }

    private static List<Template> orderedTemplates(Collection<Template> templates) {
        return templates.stream().sorted(java.util.Comparator.comparing(template -> template.getKey().toString())).toList();
    }

    public boolean hasNamespace(String namespace) {
        if (namespace == null) return false;
        String normalized = namespace.toLowerCase(Locale.ROOT);
        return fullyQualifiedTemplates.keySet().stream().anyMatch(k -> k.getNamespace().equals(normalized));
    }

    private Template readTemplate(File file, String namespace, DataKey key) throws TemplateLoadException {
        String path = key.name().toLowerCase(Locale.ROOT);
        ResourceLocation namespacedKey = ResourceLocation.tryBuild(namespace.toLowerCase(Locale.ROOT), path);
        if (path.isEmpty() || namespacedKey == null)
            throw new TemplateLoadException(
                    "Template name '" + key.name() + "' in namespace '" + namespace + "' is not a valid key");
        if (fullyQualifiedTemplates.containsKey(namespacedKey))
            throw new TemplateLoadException("Duplicate template key " + namespacedKey);

        return decodeTemplate(file.getParentFile(), namespacedKey, key);
    }

    private Template decodeTemplate(File folder, ResourceLocation namespacedKey, DataKey key) throws TemplateLoadException {
        TemplateErrorReporter errors = new TemplateErrorReporter();
        Template template;
        try {
            template = Template.load(errors, new TemplateWorkspace(folder), namespacedKey, key);
        } catch (Exception exception) {
            exception.printStackTrace();
            throw new TemplateLoadException("Unable to decode template " + namespacedKey + ": " + exception.getMessage(), exception);
        }
        if (!errors.errors.isEmpty())
            throw new TemplateLoadException(Joiner.on('\n').join(errors.errors));

        return template;
    }

    private void loadTemplates(Path folder) {
        try {
            try (var loose = Files.walk(folder, 1)) {
                loose.sorted().forEach(path -> {
                    File namespaceFile = path.toFile();
                    if (namespaceFile.isFile() && namespaceFile.getName().endsWith(".yml")) {
                        try {
                            migrateOldTemplate(folder.toFile(), namespaceFile);
                        } catch (TemplateLoadException ex) {
                            if (strict) throw new IllegalStateException("Unable to migrate template " + namespaceFile, ex);
                            Messaging.severe("Error migrating " + namespaceFile.getName() + ": " + ex.getMessage());
                        }
                    }
                });
            }
            try (var namespaces = Files.walk(folder, 1)) {
                namespaces.sorted().forEach(namespacePath -> {
                    File namespaceFile = namespacePath.toFile();
                    if (!namespaceFile.isDirectory() || namespaceFile.getName().contains(":")
                            || namespacePath.equals(folder))
                        return;
                    try (var files = Files.walk(namespacePath, 1)) {
                        files.sorted().forEach(templatePath -> {
                            File templateFile = templatePath.toFile();
                            if (templateFile.isFile() && templateFile.getName().endsWith(".yml")) {
                                try {
                                    loadTemplatesFromYamlFile(namespaceFile.getName(), templateFile);
                                } catch (TemplateLoadException ex) {
                                    if (strict) throw new IllegalStateException("Unable to load template file " + templateFile, ex);
                                    Messaging.severe(
                                            "Error loading " + templateFile.getName() + ": " + ex.getMessage());
                                }
                            }
                        });
                    } catch (IOException ex) {
                        if (strict) throw new IllegalStateException("Unable to read template namespace " + namespacePath, ex);
                        ex.printStackTrace();
                    }
                });
            }
            Messaging.log("Loaded", fullyQualifiedTemplates.size(), "templates.");
        } catch (IOException ex) {
            if (strict) throw new IllegalStateException("Unable to read template folder " + folder, ex);
            ex.printStackTrace();
        }
    }

    private void loadTemplatesFromYamlFile(String namespace, File file) throws TemplateLoadException {
        YamlStorage storage = new YamlStorage(file);
        if (!storage.load())
            throw new TemplateLoadException("Unable to load " + file.getName());

        Map<ResourceLocation, Template> prepared = new java.util.LinkedHashMap<>();
        Map<ResourceLocation, String> sourceKeys = new HashMap<>();
        for (DataKey templateKey : storage.getKey("").getSubKeys()) {
            Template template = readTemplate(file, namespace, templateKey);
            if (prepared.putIfAbsent(template.getKey(), template) != null)
                throw new TemplateLoadException("Duplicate template key " + template.getKey());
            sourceKeys.put(template.getKey(), templateKey.name());
        }
        fullyQualifiedTemplates.putAll(prepared);
        prepared.forEach((key, template) -> {
            templatesByName.put(key.getPath(), template);
            templateSources.put(key, file);
            templateSourceKeys.put(key, sourceKeys.get(key));
        });
    }

    private void migrateOldTemplate(File folder, File template) throws TemplateLoadException {
        Messaging.log("Migrating template", template.getName());
        File archive = new File(folder, template.getName() + ".migrated");
        if (archive.exists()) throw new TemplateLoadException("Migration archive already exists: " + archive);
        Storage storage = new YamlStorage(template);
        if (!storage.load())
            throw new TemplateLoadException("Unable to migrate " + template.getName());

        File namespaceFolder = new File(folder, "migrated");
        String templateName = template.getName().substring(0, template.getName().length() - ".yml".length());
        if (templateName.isEmpty() || ResourceLocation.tryBuild(namespaceFolder.getName(), templateName.toLowerCase(Locale.ROOT)) == null)
            throw new TemplateLoadException("Invalid legacy template name: " + template.getName());
        Map<String, Object> migrated;
        try { migrated = Map.of("yaml_replace", YamlReplacementAction.validatedValues(storage.getKey(""))); }
        catch (RuntimeException failure) {
            throw new TemplateLoadException("Invalid legacy template contents: " + template.getName(), failure);
        }
        if (!namespaceFolder.exists() && !namespaceFolder.mkdir())
            throw new TemplateLoadException(
                    "Unable to create destination folder while migrating " + template.getName());

        Storage destination = new YamlStorage(new File(namespaceFolder, template.getName()));
        if (!destination.load())
            throw new TemplateLoadException("Unable to open migration destination for " + template.getName());

        Map<String, Object> templates = new java.util.LinkedHashMap<>(destination.getKey("").getValuesDeep());
        var matches = templates.keySet().stream().filter(name -> name.equalsIgnoreCase(templateName)).toList();
        if (matches.size() > 1 || !matches.isEmpty() && !sameMigration(migrated, templates.get(matches.get(0))))
            throw new TemplateLoadException("Conflicting migration destination for " + template.getName());
        if (matches.isEmpty()) {
            templates.put(templateName, migrated);
            destination.getKey("").setRaw("", templates);
            destination.save();
        }

        try { Files.move(template.toPath(), archive.toPath()); }
        catch (IOException failure) { throw new TemplateLoadException("Unable to finish template migration " + template, failure); }
    }

    private static boolean sameMigration(Map<String, Object> migrated, Object existing) {
        if (!(existing instanceof Map)) return false;
        DataKey normalized = new MemoryDataKey();
        normalized.setRaw("", existing);
        DataKey action = normalized.getRelative("yaml_replace");
        Map<String, Object> fields = new java.util.LinkedHashMap<>(action.getValuesDeep());
        try { fields.putAll(YamlReplacementAction.validatedValues(action)); }
        catch (IllegalArgumentException invalid) { return false; }
        normalized.setRaw("yaml_replace", fields);
        return migrated.equals(normalized.getValuesDeep());
    }

    public static class TemplateErrorReporter {
        private final List<String> errors = new ArrayList<>();

        public void addError(String message) {
            errors.add(message);
        }
    }

    @SuppressWarnings("serial")
    private static class TemplateLoadException extends Exception {
        public TemplateLoadException(String message) {
            super(message);
        }

        public TemplateLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
