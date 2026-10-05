package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateWorkspaceTest {
    @Test void resolvesDirectFilesAndNormalizedRelativeNames(@TempDir Path root) throws Exception {
        Path namespace = Files.createDirectory(root.resolve("namespace"));
        Path file = Files.writeString(namespace.resolve("tree.yml"), "tree");
        var workspace = new TemplateWorkspace(namespace.toFile());
        assertEquals(file.toRealPath().toFile(), workspace.getFile("tree.yml"));
        assertEquals(file.toRealPath().toFile(), workspace.getFile("./tree.yml"));
    }

    @Test void rejectsDirectoriesMissingFilesAndInvalidNames(@TempDir Path namespace) throws Exception {
        Files.createDirectory(namespace.resolve("directory.yml"));
        var workspace = new TemplateWorkspace(namespace.toFile());
        assertNull(workspace.getFile("directory.yml"));
        assertNull(workspace.getFile("missing.yml"));
        assertNull(workspace.getFile(""));
        assertNull(workspace.getFile(null));
        assertNull(workspace.getFile("invalid\u0000name"));
    }

    @Test void retainsDirectChildScope(@TempDir Path root) throws Exception {
        Path namespace = Files.createDirectory(root.resolve("namespace"));
        Path external = Files.writeString(root.resolve("other.yml"), "external");
        Path nested = Files.createDirectory(namespace.resolve("nested"));
        Files.writeString(nested.resolve("tree.yml"), "nested");
        var workspace = new TemplateWorkspace(namespace.toFile());
        assertNull(workspace.getFile("../other.yml"));
        assertNull(workspace.getFile(external.toString()));
        assertNull(workspace.getFile("nested/tree.yml"));
    }
}
