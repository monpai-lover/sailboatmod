package com.monpai.sailboatmod.market.web;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record MarketPlayerIdentity(
        UUID playerUuid,
        String playerName,
        ServerPlayer onlinePlayer
) {
    public MarketPlayerIdentity {
        playerName = playerName == null ? "" : playerName.trim();
    }

    public String playerUuidString() {
        return playerUuid == null ? "" : playerUuid.toString();
    }
}

