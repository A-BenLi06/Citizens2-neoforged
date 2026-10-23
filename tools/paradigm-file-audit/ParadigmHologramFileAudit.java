import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/** Reproduces the installed provider's file lifecycle using its packaged implementation. */
public final class ParadigmHologramFileAudit {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectory(directory);
        Path path = directory.resolve("holograms.json");
        var store = Class.forName("eu.avalanche7.paradigm.modules.holograms.HologramStore");
        var constructor = store.getConstructor(Supplier.class, org.slf4j.Logger.class);
        var enabled = store.getMethod("globallyEnabled");
        Supplier<Path> location = () -> path;
        Object first = constructor.newInstance(location, null);
        if (!Boolean.TRUE.equals(enabled.invoke(first))) throw new AssertionError("Fresh provider defaults disabled");
        byte[] original = Files.readAllBytes(path);
        Object second = constructor.newInstance(location, null);
        if (!Boolean.FALSE.equals(enabled.invoke(second))) throw new AssertionError("Expected Windows existing-file reproduction");
        if (!java.util.Arrays.equals(original, Files.readAllBytes(path))) throw new AssertionError("Original configuration changed");
        if (!Files.isRegularFile(path.resolveSibling("holograms.json.tmp"))) throw new AssertionError("Missing failed replacement evidence");
        store.getMethod("reload").invoke(first);
        if (!Boolean.FALSE.equals(enabled.invoke(first))) throw new AssertionError("Expected same-store reload reproduction");
        // A closed-reader move succeeds on this exact path and filesystem.
        Path temp = path.resolveSibling("holograms.json.tmp");
        Files.move(temp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (!java.util.Arrays.equals(original, Files.readAllBytes(path))) throw new AssertionError("Closed-reader replacement changed bytes");
        System.out.println("REPRODUCED installed Paradigm: fresh enabled; existing/reloaded disabled; original bytes retained; closed-reader replacement succeeds");
    }
}
