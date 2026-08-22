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
    private final Map<ResourceLocation, Template> fullyQualifiedTemplates = new HashMap<>();
    private final Multimap<String, Template> templatesByName = HashMultimap.create();

    public TemplateRegistry(Path folder) {
        this.baseFolder = folder;
        folder.toFile().mkdirs();
        loadTemplates(baseFolder);
    }

    /** Writes an NPC out as a {@code yaml_replace} template, then reloads the file it landed in. */
    public void generateTemplateFromNPC(ResourceLocation key, NPC npc) {
        File namespaceFolder = new File(baseFolder.toFile(), key.getNamespace());
        namespaceFolder.mkdirs();
        File generatedFile = new File(namespaceFolder, "templates.yml");
        Storage templateStorage = new YamlStorage(generatedFile);
        if (!templateStorage.load())
            throw new IllegalStateException("could not load " + generatedFile);

        DataKey root = templateStorage.getKey(key.getPath()).getRelative("yaml_replace");
        npc.save(root.getRelative("replacements"));
        root.setBoolean("override", true);
        // the uuid is the one thing that must not be copied: two NPCs sharing it would collide in the registry
        root.removeKey("replacements.uuid");
        templateStorage.save();
        try {
            fullyQualifiedTemplates.remove(key);
            loadTemplatesFromYamlFile(key.getNamespace(), generatedFile);
        } catch (TemplateLoadException ex) {
            Messaging.severe("Error reloading generated template " + key + ": " + ex.getMessage());
        }
    }

    public Collection<Template> getAllTemplates() {
        return fullyQualifiedTemplates.values();
    }

    public Template getTemplateByKey(ResourceLocation key) {
        return fullyQualifiedTemplates.get(key);
    }

    /** @return every template with this bare name, across all namespaces */
    public Collection<Template> getTemplates(String name) {
        return templatesByName.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean hasNamespace(String namespace) {
        return fullyQualifiedTemplates.keySet().stream().anyMatch(k -> k.getNamespace().equals(namespace));
    }

    private void loadTemplate(File folder, String namespace, DataKey key) throws TemplateLoadException {
        String path = key.name().toLowerCase(Locale.ROOT);
        ResourceLocation namespacedKey = ResourceLocation.tryBuild(namespace.toLowerCase(Locale.ROOT), path);
        if (namespacedKey == null)
            throw new TemplateLoadException(
                    "Template name '" + key.name() + "' in namespace '" + namespace + "' is not a valid key");
        if (fullyQualifiedTemplates.containsKey(namespacedKey))
            throw new TemplateLoadException("Duplicate template key " + namespacedKey);

        TemplateErrorReporter errors = new TemplateErrorReporter();
        Template template;
        try {
            template = Template.load(errors, new TemplateWorkspace(folder), namespacedKey, key);
        } catch (Exception exception) {
            exception.printStackTrace();
            throw new TemplateLoadException(String.valueOf(exception.getMessage()));
        }
        if (!errors.errors.isEmpty())
            throw new TemplateLoadException(Joiner.on('\n').join(errors.errors));

        fullyQualifiedTemplates.put(namespacedKey, template);
        templatesByName.put(namespacedKey.getPath(), template);
    }

    private void loadTemplates(Path folder) {
        try {
            try (var loose = Files.walk(folder, 1)) {
                loose.forEach(path -> {
                    File namespaceFile = path.toFile();
                    if (namespaceFile.isFile() && namespaceFile.getName().endsWith(".yml")) {
                        try {
                            migrateOldTemplate(folder.toFile(), namespaceFile);
                        } catch (TemplateLoadException ex) {
                            Messaging.severe("Error migrating " + namespaceFile.getName() + ": " + ex.getMessage());
                        }
                    }
                });
            }
            try (var namespaces = Files.walk(folder, 1)) {
                namespaces.forEach(namespacePath -> {
                    File namespaceFile = namespacePath.toFile();
                    if (!namespaceFile.isDirectory() || namespaceFile.getName().contains(":")
                            || namespacePath.equals(folder))
                        return;
                    try (var files = Files.walk(namespacePath, 1)) {
                        files.forEach(templatePath -> {
                            File templateFile = templatePath.toFile();
                            if (templateFile.isFile() && templateFile.getName().endsWith(".yml")) {
                                try {
                                    loadTemplatesFromYamlFile(namespaceFile.getName(), templateFile);
                                } catch (TemplateLoadException ex) {
                                    Messaging.severe(
                                            "Error loading " + templateFile.getName() + ": " + ex.getMessage());
                                }
                            }
                        });
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                });
            }
            Messaging.log("Loaded", fullyQualifiedTemplates.size(), "templates.");
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    private void loadTemplatesFromYamlFile(String namespace, File file) throws TemplateLoadException {
        YamlStorage storage = new YamlStorage(file);
        if (!storage.load())
            throw new TemplateLoadException("Unable to load " + file.getName());

        for (DataKey templateKey : storage.getKey("").getSubKeys()) {
            loadTemplate(file.getParentFile(), namespace, templateKey);
        }
    }

    private void migrateOldTemplate(File folder, File template) throws TemplateLoadException {
        Messaging.log("Migrating template", template.getName());
        Storage storage = new YamlStorage(template);
        if (!storage.load())
            throw new TemplateLoadException("Unable to migrate " + template.getName());

        File namespaceFolder = new File(folder, "migrated");
        if (!namespaceFolder.exists() && !namespaceFolder.mkdir())
            throw new TemplateLoadException(
                    "Unable to create destination folder while migrating " + template.getName());

        String templateName = template.getName().replace(".yml", "");
        Storage destination = new YamlStorage(new File(namespaceFolder, template.getName()));
        if (!destination.load())
            throw new TemplateLoadException("Unable to open migration destination for " + template.getName());

        DataKey from = storage.getKey("");
        DataKey dest = destination.getKey(templateName + ".yaml_replace");
        dest.setBoolean("override", from.getBoolean("override"));
        dest.setRaw("replacements", from.getRelative("replacements").getValuesDeep());
        destination.save();

        if (!template.renameTo(new File(folder, template.getName() + ".migrated"))) {
            Messaging.severe("Could not rename " + template.getName() + " after migrating it - it will migrate again");
        }
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
    }
}
