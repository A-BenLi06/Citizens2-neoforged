package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranslatorFormatTest {
    private static final String KEY = "citizens.commands.npc.potioneffects.effect-removed";

    @Test void malformedEditableFormatFallsBackWithoutChangingTheFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("fr.json");
        String contents = "{\"" + KEY + "\":\"Broken [[{{0}}]] [[{1}]]\"}";
        Files.writeString(file, contents);
        var field = Translator.class.getDeclaredField("INSTANCE"); field.setAccessible(true); Object previous = field.get(null);
        try {
            Translator.setInstance(directory.toFile(), Locale.FRENCH);
            assertEquals("Potion effect [[Speed]] removed from [[Guard]].", Translator.translate(KEY, "Speed", "Guard"));
            assertEquals("Potion effect [[Luck]] removed from [[Guard]].", Translator.translate(KEY, "Luck", "Guard"));
            assertEquals(contents, Files.readString(file));
        } finally { field.set(null, previous); }
    }

    @Test void validOverridesAndBracesInsideArgumentsRemainLiteral(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("fr.json"), "{\"" + KEY + "\":\"Custom [[{0}]] on [[{1}]]\"}");
        var field = Translator.class.getDeclaredField("INSTANCE"); field.setAccessible(true); Object previous = field.get(null);
        try {
            Translator.setInstance(directory.toFile(), Locale.FRENCH);
            assertEquals("Custom [[Speed {details}]] on [[Guard]]", Translator.translate(KEY, "Speed {details}", "Guard"));
        } finally { field.set(null, previous); }
    }
}
