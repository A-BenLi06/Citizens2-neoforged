package net.citizensnpcs.commands;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.CommandMessages;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.command.exception.RequirementMissingException;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.api.util.TextParser;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.SkinTrait;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.MojangSkinGenerator;
import net.citizensnpcs.util.SkinData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

/** Skin command operations; all entity mutation and permission checks stay on the server thread. */
final class NPCSkinCommands {
    private NPCSkinCommands() { }

    static void execute(CommandContext args, CommandSourceStack sender, NPC npc, String url, String file,
            BooleanSupplier authorized) throws CommandException {
        if (npc.getCosmeticEntityType() != EntityType.PLAYER)
            throw new RequirementMissingException(Messaging.tr(CommandMessages.REQUIREMENTS_INVALID_MOB_TYPE, npc.getCosmeticEntityType()));
        SkinTrait trait = npc.getTraitNullable(SkinTrait.class);
        if (args.hasFlag('c')) {
            if (trait != null) trait.clearTexture();
            Messaging.sendTr(sender, Messages.SKIN_CLEARED, npc.getName());
            return;
        }
        if (args.hasFlag('u') || args.hasFlag('e')) {
            if (trait == null || trait.getTexture() == null) throw new CommandException(Messages.SKIN_REQUIRED);
            URI texture;
            try { texture = SkinData.textureURL(trait.getTexture()); }
            catch (IllegalArgumentException invalid) { throw new CommandException(Messages.SKIN_INVALID_TEXTURE); }
            if (args.hasFlag('u')) { Messaging.send(sender, texture.toString()); return; }
            // Match the original export trust boundary; never forward redirects to another host.
            if (!"textures.minecraft.net".equalsIgnoreCase(texture.getHost()) || texture.getPort() != -1)
                throw new CommandException(Messages.ERROR_SETTING_SKIN_URL, "Mojang");
            Path target;
            String name = file == null ? npc.getUniqueId() + ".png" : file;
            try { target = skinFile(name, false); }
            catch (IOException | IllegalArgumentException invalid) { throw new CommandException(Messages.INVALID_SKIN_FILE, name); }
            export(sender, npc, trait, texture, target, authorized);
            return;
        }
        if (url != null || file != null) {
            String source = Placeholders.replace(file == null ? url : file, sender, npc);
            Path input = null;
            try {
                if (file != null) input = skinFile(source, true);
                else SkinData.httpURI(source);
            } catch (IOException | IllegalArgumentException invalid) {
                throw new CommandException(file == null ? Messages.ERROR_SETTING_SKIN_URL : Messages.INVALID_SKIN_FILE, source);
            }
            if (!authorized.getAsBoolean()) return;
            trait = npc.getOrAddTrait(SkinTrait.class);
            if (!current(npc, trait) || !authorized.getAsBoolean()) return;
            importSkin(sender, npc, trait, source, input, args.hasFlag('s'), authorized);
            return;
        }
        if (args.hasFlag('t')) {
            if (args.argsLength() != 4) throw new CommandException(Messages.SKIN_REQUIRED);
            String name = args.getString(1), value = args.getString(2), signature = args.getString(3);
            try {
                if (TextParser.strip(name).isBlank() || signature.isBlank()) throw new IllegalArgumentException();
                SkinData.textures(value);
            } catch (IllegalArgumentException invalid) { throw new CommandException(Messages.SKIN_INVALID_TEXTURE); }
            trait = npc.getOrAddTrait(SkinTrait.class);
            if (!current(npc, trait) || !authorized.getAsBoolean()) return;
            long revision = trait.getSkinRevision() + 1;
            trait.setSkinPersistent(name, signature, value);
            if (current(npc, trait) && trait.getSkinRevision() == revision && authorized.getAsBoolean())
                Messaging.sendTr(sender, Messages.SKIN_SET, npc.getName(), name);
            return;
        }
        if (args.hasFlag('s')) {
            if (!(npc.getCosmeticEntity() instanceof ServerPlayer player)) throw new CommandException(CommandMessages.MUST_BE_SPAWNED);
            Location location = args.getSenderLocation();
            if (sender.getPlayer() == null && (location == null || location.getWorld() == null))
                throw new CommandException(CommandMessages.MUST_BE_INGAME);
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            var profile = player.getGameProfile();
            var copy = new com.mojang.authlib.GameProfile(profile.getId(), profile.getName());
            copy.getProperties().putAll(profile.getProperties());
            head.set(DataComponents.PROFILE, new ResolvableProfile(copy));
            if (sender.getPlayer() != null && sender.getPlayer().getInventory().add(head)) return;
            if (location == null || location.getWorld() == null) throw new CommandException(CommandMessages.MUST_BE_INGAME);
            location.getWorld().addFreshEntity(new ItemEntity(location.getWorld(), location.getX(), location.getY(), location.getZ(), head));
            return;
        }
        if (args.argsLength() > 2) throw new CommandException(Messages.SKIN_REQUIRED);
        String name = args.argsLength() == 2 ? args.getString(1) : null;
        if (name != null && TextParser.strip(name).isBlank()) throw new CommandException(Messages.SKIN_REQUIRED);
        if (args.hasFlag('b') && name != null) {
            String prefix = Setting.BEDROCK_SKIN_PREFIX.asString();
            if (prefix.isBlank()) throw new CommandException(Messages.SKIN_BEDROCK_PREFIX_REQUIRED);
            if (!name.regionMatches(true, 0, prefix, 0, prefix.length())) name = prefix + name;
            if (name.substring(prefix.length()).isBlank()) throw new CommandException(Messages.SKIN_REQUIRED);
        }
        if (trait == null && (name != null || args.hasFlag('l'))) trait = npc.getOrAddTrait(SkinTrait.class);
        if (trait != null && (!current(npc, trait) || !authorized.getAsBoolean())) return;
        if (args.hasFlag('l')) {
            long revision = trait.getSkinRevision() + 1;
            trait.setShouldUpdateSkins(!trait.shouldUpdateSkins());
            if (!current(npc, trait) || trait.getSkinRevision() != revision || !authorized.getAsBoolean()) return;
            Messaging.sendTr(sender, trait.shouldUpdateSkins() ? Messages.SKIN_LATEST_SET : Messages.SKIN_LATEST_UNSET,
                    npc.getName(), name == null ? trait.getSkinName() : name);
        }
        if (name == null) {
            if (!args.hasFlag('l')) Messaging.sendTr(sender, Messages.SKIN_CURRENT, trait == null ? "" : java.util.Objects.toString(trait.getSkinName(), ""));
            return;
        }
        long revision = trait.getSkinRevision() + 1;
        trait.setSkinName(name, true);
        if (current(npc, trait) && trait.getSkinRevision() == revision && authorized.getAsBoolean())
            Messaging.sendTr(sender, Messages.SKIN_SET, npc.getName(), name);
    }

    private static void importSkin(CommandSourceStack sender, NPC npc, SkinTrait trait, String source, Path file,
            boolean slim, BooleanSupplier authorized) {
        long revision = trait.beginSkinRequest();
        var scheduler = CitizensAPI.getScheduler();
        Messaging.sendTr(sender, Messages.FETCHING_SKIN, source);
        scheduler.runTaskAsynchronously(() -> {
            try {
                var data = file == null ? MojangSkinGenerator.generateFromURL(source, slim)
                        : MojangSkinGenerator.generateFromPNG(readSkinFile(file), slim);
                String id = data.get("uuid").getAsString();
                var texture = data.getAsJsonObject("texture");
                String signature = texture.get("signature").getAsString(), value = texture.get("value").getAsString();
                scheduler.runTask(() -> {
                    if (!current(npc, trait) || trait.getSkinRevision() != revision || !authorized.getAsBoolean()) return;
                    trait.setSkinPersistent(id, signature, value);
                    if (current(npc, trait) && trait.getSkinRevision() == revision + 1 && authorized.getAsBoolean())
                        Messaging.sendTr(sender, Messages.SKIN_URL_SET, npc.getName(), source);
                });
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                scheduler.runTask(() -> {
                    if (current(npc, trait) && trait.getSkinRevision() == revision && authorized.getAsBoolean())
                        Messaging.sendErrorTr(sender, Messages.ERROR_SETTING_SKIN_URL, source);
                });
            }
        });
    }

    private static void export(CommandSourceStack sender, NPC npc, SkinTrait trait, URI texture, Path target, BooleanSupplier authorized) {
        long revision = trait.getSkinRevision();
        var scheduler = CitizensAPI.getScheduler();
        scheduler.runTaskAsynchronously(() -> {
            try {
                byte[] bytes = download(texture);
                SkinData.validatePNG(bytes);
                scheduler.runTask(() -> {
                    if (!current(npc, trait) || trait.getSkinRevision() != revision || !authorized.getAsBoolean()) return;
                    try {
                        writeSkinFile(target, bytes);
                        Messaging.sendTr(sender, Messages.SKIN_EXPORTED, target.getFileName());
                    } catch (IOException failure) { Messaging.sendErrorTr(sender, Messages.INVALID_SKIN_FILE, target.getFileName()); }
                });
            } catch (Exception failure) {
                scheduler.runTask(() -> {
                    if (current(npc, trait) && trait.getSkinRevision() == revision && authorized.getAsBoolean())
                        Messaging.sendErrorTr(sender, Messages.ERROR_SETTING_SKIN_URL, "Mojang");
                });
            }
        });
    }

    private static Path skinFile(String name, boolean existing) throws IOException {
        Path root = CitizensAPI.getDataFolder().toPath().resolve("skins").toAbsolutePath().normalize();
        Path relative = Path.of(name);
        if (relative.isAbsolute() || name.isBlank() || !name.toLowerCase(Locale.ROOT).endsWith(".png")) throw new IOException("Invalid skin file");
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Skin file escapes skins directory");
        if (!existing) Files.createDirectories(root);
        Path realRoot = root.toRealPath();
        if (!target.getParent().toRealPath().startsWith(realRoot)) throw new IOException("Skin directory escapes root");
        if (Files.exists(target)) {
            if (!Files.isRegularFile(target) || Files.isHidden(target) || !target.toRealPath().startsWith(realRoot))
                throw new IOException("Invalid skin file");
        } else if (existing) throw new IOException("Skin file missing");
        return target;
    }

    private static byte[] readSkinFile(Path file) throws IOException {
        Path root = CitizensAPI.getDataFolder().toPath().resolve("skins").toAbsolutePath().normalize();
        skinFile(root.relativize(file).toString(), true);
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(SkinData.MAX_IMAGE_BYTES + 1);
            SkinData.validatePNG(bytes);
            return bytes;
        }
    }

    private static void writeSkinFile(Path target, byte[] bytes) throws IOException {
        Path root = CitizensAPI.getDataFolder().toPath().resolve("skins").toAbsolutePath().normalize();
        skinFile(root.relativize(target).toString(), false);
        Path staging = Files.createTempFile(target.getParent(), ".citizens-skin-", ".tmp");
        try {
            Files.write(staging, bytes);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
    }

    private static byte[] download(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(30000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Texture download failed");
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(SkinData.MAX_IMAGE_BYTES + 1);
                if (bytes.length > SkinData.MAX_IMAGE_BYTES) throw new IOException("Skin is too large");
                return bytes;
            }
        } finally { connection.disconnect(); }
    }

    private static boolean current(NPC npc, SkinTrait trait) {
        return npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc
                && npc.getOwningRegistry().getById(npc.getId()) == npc && npc.getTraitNullable(SkinTrait.class) == trait;
    }
}
