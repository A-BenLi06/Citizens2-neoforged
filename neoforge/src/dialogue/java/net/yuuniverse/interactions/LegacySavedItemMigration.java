package net.yuuniverse.interactions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import net.citizensnpcs.util.RSItemEditorItems;
import net.minecraft.commands.CommandSourceStack;

/** Source-preserving, resumable import. Every target write has a previously persisted intent and snapshot. */
final class LegacySavedItemMigration {
    record Entry(String status, String detail, String nativeSnapshot) { }
    record Report(String source, String sha256, String timestamp, boolean apply, Map<String, Entry> entries,
            Map<String, String> legacyNicknames) { }

    static Path run(CommandSourceStack source, Path input, Path reports, boolean apply) throws IOException {
        if (!source.getServer().isSameThread()) throw new IllegalStateException("Migration requires the server thread");
        var loaded = ItemLibrary.migrationSource(input.toFile(), source.getServer().registryAccess());
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(loaded.original())); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        Path directory = reports.resolve(UUID.randomUUID().toString());
        Files.createDirectories(directory);
        Files.write(directory.resolve("source.yml"), loaded.original(), java.nio.file.StandardOpenOption.CREATE_NEW);
        Map<String, Entry> entries = new TreeMap<>();
        loaded.unavailable().forEach((id, reason) -> entries.put(id, new Entry("UNAVAILABLE", reason, null)));
        String timestamp = java.time.OffsetDateTime.now().toString();
        Path output = directory.resolve("report.json");
        for (var entry : new TreeMap<>(loaded.snapshots()).entrySet()) {
            String id = entry.getKey();
            String snapshot = null;
            try {
                snapshot = entry.getValue().save(source.getServer().registryAccess()).toString();
                var result = RSItemEditorItems.importShared(source, id, entry.getValue(), false);
                entries.put(id, new Entry(result.name(), null, snapshot));
            } catch (RuntimeException failure) {
                entries.put(id, new Entry("FAILED", failure.toString(), snapshot));
            }
        }
        write(output, new Report(input.toAbsolutePath().toString(), hash, timestamp, apply, entries, loaded.legacyNicknames()));
        if (apply) {
            for (var entry : new TreeMap<>(loaded.snapshots()).entrySet()) {
                String id = entry.getKey();
                Entry planned = entries.get(id);
                if (!planned.status().equals("WOULD_IMPORT")) continue;
                entries.put(id, new Entry("IMPORTING", null, planned.nativeSnapshot()));
                write(output, new Report(input.toAbsolutePath().toString(), hash, timestamp, true, entries, loaded.legacyNicknames()));
                try {
                    var result = RSItemEditorItems.importShared(source, id, entry.getValue(), true);
                    entries.put(id, new Entry(result.name(), null, planned.nativeSnapshot()));
                } catch (RuntimeException failure) {
                    entries.put(id, new Entry("FAILED", failure.toString(), planned.nativeSnapshot()));
                }
                write(output, new Report(input.toAbsolutePath().toString(), hash, timestamp, true, entries, loaded.legacyNicknames()));
            }
        }
        return output;
    }

    private static void write(Path output, Report report) throws IOException {
        Path temporary = output.resolveSibling("report.tmp");
        Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
