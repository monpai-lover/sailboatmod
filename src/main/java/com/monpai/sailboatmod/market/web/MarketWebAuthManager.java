package com.monpai.sailboatmod.market.web;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketWebAuthManager {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MinecraftServer minecraftServer;
    private final Map<String, SessionToken> sessions = new ConcurrentHashMap<>();

    public MarketWebAuthManager(MinecraftServer minecraftServer) {
        this.minecraftServer = minecraftServer;
    }

    public String issueLoginToken(ServerPlayer player, int ttlMinutes) {
        if (player == null || minecraftServer == null) {
            return "";
        }
        String playerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        return MarketWebTokenSavedData.get(minecraftServer.overworld()).getOrCreateToken(player.getUUID(), playerName);
    }

    public SessionToken exchangeLoginToken(String loginToken) {
        if (loginToken == null || loginToken.isBlank() || minecraftServer == null) {
            return null;
        }
        MarketWebTokenSavedData.TokenEntry token = MarketWebTokenSavedData.get(minecraftServer.overworld()).resolveToken(loginToken);
        if (token == null) {
            return null;
        }
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(token.playerUuid());
        } catch (IllegalArgumentException exception) {
            return null;
        }
        String sessionId = randomToken();
        SessionToken session = new SessionToken(
                sessionId,
                playerUuid,
                token.playerName(),
                System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L
        );
        sessions.put(sessionId, session);
        return session;
    }

    public SessionToken resolveSession(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return null;
        }
        SessionToken session = sessions.get(sessionToken);
        if (session == null) {
            return null;
        }
        if (session.expiresAtMs() < System.currentTimeMillis()) {
            sessions.remove(sessionToken);
            return null;
        }
        return session;
    }

    public MarketPlayerIdentity resolveIdentity(MinecraftServer server, String sessionToken) {
        SessionToken session = resolveSession(sessionToken);
        if (session == null) {
            return null;
        }
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(session.playerUuid());
        return new MarketPlayerIdentity(session.playerUuid(), session.playerName(), player);
    }

    public void clear() {
        sessions.clear();
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record SessionToken(String token, UUID playerUuid, String playerName, long expiresAtMs) {
    }
}
