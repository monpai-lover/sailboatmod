package com.monpai.sailboatmod.network;

import com.monpai.sailboatmod.compat.ItemStackNbt;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/** Item components require a registry-aware buffer in Minecraft 1.21.1. */
public final class PacketItemCodec {
    private PacketItemCodec() {}

    private static RegistryFriendlyByteBuf registry(FriendlyByteBuf buffer) {
        return buffer instanceof RegistryFriendlyByteBuf registry ? registry
                : new RegistryFriendlyByteBuf(buffer, (net.minecraft.core.RegistryAccess) ItemStackNbt.provider());
    }

    public static void write(FriendlyByteBuf buffer, ItemStack stack) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(registry(buffer), stack);
    }

    public static ItemStack read(FriendlyByteBuf buffer) {
        return ItemStack.OPTIONAL_STREAM_CODEC.decode(registry(buffer));
    }
}
