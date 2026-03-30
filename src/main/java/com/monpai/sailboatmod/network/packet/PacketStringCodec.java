package com.monpai.sailboatmod.network.packet;

import net.minecraft.network.FriendlyByteBuf;

final class PacketStringCodec {
    private PacketStringCodec() {
    }

    static void writeUtfSafe(FriendlyByteBuf buffer, String value, int maxLength) {
        buffer.writeUtf(trimToLength(value, maxLength), maxLength);
    }

    static String trimToLength(String value, int maxLength) {
        if (value == null || value.isEmpty() || maxLength <= 0) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
