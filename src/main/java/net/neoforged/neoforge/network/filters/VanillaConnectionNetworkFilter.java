/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.filters;

import com.google.common.collect.ImmutableMap;
import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelHandler;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.registries.RegistryManager;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

/**
 * A filter for impl packets, used to filter/modify parts of vanilla impl messages that
 * will cause errors or warnings on vanilla clients, for example entity attributes that are added by Forge or mods.
 */
@ApiStatus.Internal
@ChannelHandler.Sharable
public class VanillaConnectionNetworkFilter extends VanillaPacketFilter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ConnectionType connectionType;

    public VanillaConnectionNetworkFilter(ConnectionType connectionType) {
        super(
                ImmutableMap.<Class<? extends Packet<?>>, BiConsumer<Packet<?>, List<? super Packet<?>>>>builder()
                        .put(handler(ClientboundUpdateAttributesPacket.class, VanillaConnectionNetworkFilter::filterEntityProperties))
                        .put(handler(ClientboundCommandsPacket.class, VanillaConnectionNetworkFilter::filterCommandList))
                        .put(handler(ClientboundSystemChatPacket.class, VanillaConnectionNetworkFilter::filterSystemChat))
                        .put(handler(ClientboundUpdateTagsPacket.class, VanillaConnectionNetworkFilter::filterCustomTagTypes))
                        .build());

        this.connectionType = connectionType;
    }

    @Override
    public boolean isNecessary(Connection manager) {
        return !connectionType.isNeoForge();
    }

    /**
     * Filter for SEntityPropertiesPacket. Filters out any entity attributes that are not in the "minecraft" namespace.
     * A vanilla client would ignore these with an error log.
     */
    private static ClientboundUpdateAttributesPacket filterEntityProperties(ClientboundUpdateAttributesPacket msg) {
        ClientboundUpdateAttributesPacket newPacket = new ClientboundUpdateAttributesPacket(msg.getEntityId(), Collections.emptyList());
        msg.getValues().stream()
                .filter(snapshot -> isVanillaAttribute(snapshot.attribute()))
                .forEach(snapshot -> newPacket.getValues().add(snapshot));
        return newPacket;
    }

    private static boolean isVanillaAttribute(Holder<Attribute> holder) {
        Identifier key = holder.unwrapKey().map(ResourceKey::identifier).orElse(null);
        return key != null && key.getNamespace().equals("minecraft");
    }

    /**
     * Filter for SCommandListPacket. Uses {@link CommandTreeCleaner} to filter out any ArgumentTypes that are not in the "minecraft" or "brigadier" namespace.
     * A vanilla client would fail to deserialize the packet and disconnect with an error message if these were sent.
     */
    private static ClientboundCommandsPacket filterCommandList(ClientboundCommandsPacket packet) {
        CommandBuildContext commandBuildContext = Commands.createValidationContext(VanillaRegistries.createLookup());
        var root = packet.getRoot(commandBuildContext, CommandTreeCleaner.COMMAND_NODE_BUILDER);
        var newRoot = CommandTreeCleaner.cleanArgumentTypes(root, argType -> {
            ArgumentTypeInfo<?, ?> info = ArgumentTypeInfos.byClass(argType);
            Identifier id = BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(info);
            return id != null && (id.getNamespace().equals("minecraft") || id.getNamespace().equals("brigadier"));
        });
        return new ClientboundCommandsPacket(newRoot, CommandTreeCleaner.COMMAND_NODE_INSPECTOR);
    }

    /// Filters out custom attributes from items in components that the client won't recognize.
    /// A vanilla client would fail to deserialize the packet and disconnect with an error message if these were sent.
    private static ClientboundSystemChatPacket filterSystemChat(ClientboundSystemChatPacket packet) {
        Component content = packet.content();
        if (content.getContents() instanceof TranslatableContents contents) {
            Object[] args = contents.getArgs();
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Component subArg && subArg.getStyle().getHoverEvent() instanceof HoverEvent.ShowItem(ItemStackTemplate template)) {
                    ItemAttributeModifiers modifiers = template.get(DataComponents.ATTRIBUTE_MODIFIERS);
                    if (modifiers != null) {
                        List<ItemAttributeModifiers.Entry> adjustedModifiers = modifiers.modifiers().stream()
                                .filter(modifier -> isVanillaAttribute(modifier.attribute()))
                                .toList();
                        if (adjustedModifiers.size() != modifiers.modifiers().size()) {
                            ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
                            for (ItemAttributeModifiers.Entry adjustedModifier : adjustedModifiers) {
                                builder.add(adjustedModifier.attribute(), adjustedModifier.modifier(), adjustedModifier.slot(), adjustedModifier.display());
                            }
                            DataComponentPatch.Builder patchBuilder = DataComponentPatch.builder();
                            for (Map.Entry<DataComponentType<?>, Optional<?>> entry : template.components().entrySet()) {
                                DataComponentType<?> key = entry.getKey();
                                Optional<?> value = entry.getValue();
                                if (value.isPresent()) {
                                    if (key == DataComponents.ATTRIBUTE_MODIFIERS) {
                                        patchBuilder.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
                                    } else {
                                        patchBuilder.set((DataComponentType) key, value.get());
                                    }
                                } else {
                                    patchBuilder.remove(key);
                                }
                            }
                            ItemStackTemplate adjustedTemplate = new ItemStackTemplate(template.item(), template.count(), patchBuilder.build());
                            //Abuse the fact that the backing array is mutable
                            //TODO: Should we just entirely recreate the object?
                            args[i] = subArg.copy().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowItem(adjustedTemplate)));
                        }
                    }
                }
            }
            //return new ClientboundSystemChatPacket(content, packet.overlay());
        }
        return packet;
    }

    /**
     * Filters out custom tag types that the vanilla client won't recognize.
     * It prevents a rare error from logging and reduces the packet size
     */
    private static ClientboundUpdateTagsPacket filterCustomTagTypes(ClientboundUpdateTagsPacket packet) {
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = packet.getTags()
                .entrySet().stream().filter(e -> isVanillaRegistry(e.getKey().identifier()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return new ClientboundUpdateTagsPacket(tags);
    }

    private static boolean isVanillaRegistry(Identifier location) {
        // Checks if the registry name is contained within the static view of both BuiltInRegistries and VanillaRegistries
        return RegistryManager.getVanillaRegistryKeys().contains(location)
                || VanillaRegistries.DATAPACK_REGISTRY_KEYS.stream().anyMatch(k -> k.identifier().equals(location));
    }
}
