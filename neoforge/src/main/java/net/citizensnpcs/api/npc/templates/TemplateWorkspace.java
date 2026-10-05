package net.citizensnpcs.api.npc.templates;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public class TemplateWorkspace {
    private final File namespaceFolder;

    public TemplateWorkspace(File namespaceFolder) {
        this.namespaceFolder = namespaceFolder;
    }

    public File getFile(String fileName) {
        if (fileName == null) return null;
        try {
            Path name = Path.of(fileName);
            if (name.isAbsolute()) return null;
            Path base = namespaceFolder.toPath().toRealPath();
            Path candidate = base.resolve(name).normalize();
            if (!base.equals(candidate.getParent()) || !Files.isRegularFile(candidate)) return null;
            Path resolved = candidate.toRealPath();
            return base.equals(resolved.getParent()) ? resolved.toFile() : null;
        } catch (IOException | InvalidPathException failure) {
            return null;
        }
    }
}
