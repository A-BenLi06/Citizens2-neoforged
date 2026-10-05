package net.citizensnpcs.api.npc.templates;

import java.io.File;

public class TemplateWorkspace {
    private final File namespaceFolder;

    public TemplateWorkspace(File namespaceFolder) {
        this.namespaceFolder = namespaceFolder;
    }

    public File getFile(String fileName) {
        File test = new File(namespaceFolder, fileName);
        return test.exists() && test.getParentFile().equals(namespaceFolder) ? test : null;
    }
}
