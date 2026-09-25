package net.citizensnpcs.api.util;

import java.util.Optional;

import com.mojang.brigadier.StringReader;
import com.mojang.serialization.Dynamic;

import net.kyori.adventure.text.BlockNBTComponent;
import net.kyori.adventure.text.EntityNBTComponent;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.ScoreComponent;
import net.kyori.adventure.text.SelectorComponent;
import net.kyori.adventure.text.StorageNBTComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.DataComponentValue;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.BlockDataSource;
import net.minecraft.network.chat.contents.EntityDataSource;
import net.minecraft.network.chat.contents.StorageDataSource;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Converts the parsed tree, preserving native contents and registry-backed hover payloads without a Bukkit runtime. */
final class MiniMessageComponents {
    private MiniMessageComponents() {
    }

    static MutableComponent convert(net.kyori.adventure.text.Component input) {
        MutableComponent result;
        if (input instanceof TextComponent text) {
            result = Component.literal(text.content());
        } else if (input instanceof TranslatableComponent translated) {
            Object[] arguments = translated.arguments().stream().map(argument -> argument.value()
                    instanceof net.kyori.adventure.text.Component component ? convert(component) : argument.value())
                    .toArray();
            result = Component.translatableWithFallback(translated.key(), translated.fallback(), arguments);
        } else if (input instanceof KeybindComponent keybind) {
            result = Component.keybind(keybind.keybind());
        } else if (input instanceof ScoreComponent score) {
            result = Component.score(score.name(), score.objective());
        } else if (input instanceof SelectorComponent selector) {
            result = Component.selector(selector.pattern(), optional(selector.separator()));
        } else if (input instanceof BlockNBTComponent nbt) {
            result = Component.nbt(nbt.nbtPath(), nbt.interpret(), optional(nbt.separator()),
                    new BlockDataSource(nbt.pos().asString()));
        } else if (input instanceof EntityNBTComponent nbt) {
            result = Component.nbt(nbt.nbtPath(), nbt.interpret(), optional(nbt.separator()),
                    new EntityDataSource(nbt.selector()));
        } else if (input instanceof StorageNBTComponent nbt) {
            result = Component.nbt(nbt.nbtPath(), nbt.interpret(), optional(nbt.separator()),
                    new StorageDataSource(ResourceLocation.parse(nbt.storage().asString())));
        } else {
            throw new IllegalArgumentException("Unsupported native component type: " + input.getClass().getName());
        }
        result.setStyle(style(input.style()));
        for (var child : input.children()) {
            result.append(convert(child));
        }
        return result;
    }

    private static Optional<Component> optional(net.kyori.adventure.text.Component component) {
        return component == null ? Optional.empty() : Optional.of(convert(component));
    }

    private static Style style(net.kyori.adventure.text.format.Style input) {
        if (input.shadowColor() != null)
            throw new IllegalArgumentException("Shadow colours require a newer Minecraft text protocol");
        Style result = Style.EMPTY.withBold(decoration(input, TextDecoration.BOLD))
                .withItalic(decoration(input, TextDecoration.ITALIC))
                .withUnderlined(decoration(input, TextDecoration.UNDERLINED))
                .withStrikethrough(decoration(input, TextDecoration.STRIKETHROUGH))
                .withObfuscated(decoration(input, TextDecoration.OBFUSCATED)).withInsertion(input.insertion());
        if (input.color() != null) {
            result = result.withColor(input.color().value());
        }
        if (input.font() != null) {
            result = result.withFont(ResourceLocation.parse(input.font().asString()));
        }
        if (input.clickEvent() != null) {
            var event = input.clickEvent();
            ClickEvent.Action action = java.util.Arrays.stream(ClickEvent.Action.values())
                    .filter(candidate -> candidate.getSerializedName().equals(event.action().toString()))
                    .findFirst().orElse(null);
            if (action == null || ClickEvent.Action.filterForSerialization(action).error().isPresent())
                throw new IllegalArgumentException("Unsupported native click action: " + event.action());
            result = result.withClickEvent(new ClickEvent(action, event.value()));
        }
        if (input.hoverEvent() != null) {
            result = result.withHoverEvent(hover(input.hoverEvent()));
        }
        return result;
    }

    private static Boolean decoration(net.kyori.adventure.text.format.Style style, TextDecoration decoration) {
        return switch (style.decoration(decoration)) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> null;
        };
    }

    private static HoverEvent hover(net.kyori.adventure.text.event.HoverEvent<?> event) {
        if (event.value() instanceof net.kyori.adventure.text.Component text)
            return new HoverEvent(HoverEvent.Action.SHOW_TEXT, convert(text));
        if (event.value() instanceof net.kyori.adventure.text.event.HoverEvent.ShowEntity entity) {
            ResourceLocation id = ResourceLocation.parse(entity.type().asString());
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown hover entity: " + id));
            return new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
                    new HoverEvent.EntityTooltipInfo(type, entity.id(), optional(entity.name())));
        }
        if (event.value() instanceof net.kyori.adventure.text.event.HoverEvent.ShowItem item)
            return new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(item(item)));
        throw new IllegalArgumentException("Unsupported native hover action: " + event.action());
    }

    @SuppressWarnings("deprecation")
    private static ItemStack item(net.kyori.adventure.text.event.HoverEvent.ShowItem item) {
        try {
            CompoundTag root = new CompoundTag();
            root.putString("id", item.item().asString());
            if (item.nbt() != null) {
                // MiniMessage's legacy SNBT form is the pre-component item tag. Use the final release schema that
                // stored this format (1.20.4), then Minecraft's own item fixes, including custom-data preservation.
                root.putByte("Count", (byte) 1);
                root.put("tag", TagParser.parseTag(item.nbt().string()));
                root = (CompoundTag) DataFixers.getDataFixer().update(References.ITEM_STACK,
                        new Dynamic<>(NbtOps.INSTANCE, root), LEGACY_ITEM_DATA_VERSION,
                        SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
            } else {
                CompoundTag components = new CompoundTag();
                for (var entry : item.dataComponents().entrySet()) {
                    if (entry.getValue() instanceof DataComponentValue.Removed) {
                        components.put("!" + entry.getKey().asString(), new CompoundTag());
                    } else if (entry.getValue() instanceof DataComponentValue.TagSerializable value) {
                        components.put(entry.getKey().asString(),
                                new TagParser(new StringReader(value.asBinaryTag().string())).readValue());
                    } else {
                        throw new IllegalArgumentException("Unsupported item component encoding: " + entry.getKey());
                    }
                }
                root.put("components", components);
            }
            root.putInt("count", item.count());
            var server = ServerLifecycleHooks.getCurrentServer();
            HolderLookup.Provider registries = server == null ? RegistryAccess.EMPTY : server.registryAccess();
            return ItemStack.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, registries), root)
                    .getOrThrow(IllegalArgumentException::new);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ex) {
            throw new IllegalArgumentException("Invalid item hover SNBT", ex);
        }
    }

    private static final int LEGACY_ITEM_DATA_VERSION = 3700;
}
