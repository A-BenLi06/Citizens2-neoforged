package net.citizensnpcs.api.persistence;

import com.google.common.io.BaseEncoding;

import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Persists a chat {@link Component} as base64-encoded JSON, byte-for-byte the same layout upstream produces with
 * Adventure's {@code GsonComponentSerializer} — the JSON dialect is identical, so existing saves round-trip.
 * <p>
 * Serialising a Component in 1.21.1 needs a {@link HolderLookup.Provider} to resolve registry references. The running
 * server supplies one; if there is none (data touched outside a server session) the built-in empty registry access is
 * used, which is enough for plain text and styling.
 */
public class ComponentPersister implements Persister<Component> {
    @Override
    public Component create(DataKey root) {
        String encoded = root.getString("");
        if (encoded == null || encoded.isEmpty())
            return null;
        try {
            String json = new String(BaseEncoding.base64().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
            return Component.Serializer.fromJson(json, provider());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void save(Component text, DataKey root) {
        String json = Component.Serializer.toJson(text, provider());
        root.setString("", BaseEncoding.base64().encode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static HolderLookup.Provider provider() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.registryAccess() : RegistryAccess.EMPTY;
    }
}
