package net.citizensnpcs.api.util;
import java.nio.file.Files;
import java.nio.file.Path;
public class ReadFixture {
    public static void main(String[] args) throws Exception {
        for (String file : args) {
            if (Path.of(file).getFileName().toString().equals("cycle.base64")) {
                try { LegacyBukkitData.read(Files.readString(Path.of(file))); throw new AssertionError("Cycle accepted"); }
                catch (IllegalArgumentException expected) { System.out.println("cycle.base64: rejected"); continue; }
            }
            var decoded = LegacyBukkitData.read(Files.readString(Path.of(file)));
            if (Path.of(file).getFileName().toString().equals("long.base64")
                    && !"中\u0000😀".repeat(15000).equals(decoded.get("display-name"))) throw new AssertionError("Long modified UTF changed");
            System.out.println(Path.of(file).getFileName() + ": " + decoded.keySet());
        }
    }
}
