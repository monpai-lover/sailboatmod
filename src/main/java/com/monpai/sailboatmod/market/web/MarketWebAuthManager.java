package com.monpai.sailboatmod.market.web;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MarketWebAuthManager {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PASSWORD_ITERATIONS = 120_000;
    private static final int PASSWORD_KEY_LENGTH = 256;

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
        return createSession(playerUuid, token.playerName());
    }

    public SessionToken exchangeLoginTokenAndBindAccount(String loginToken, String username, String password) {
        if (minecraftServer == null) {
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
        if (!bindAccount(playerUuid, token.playerName(), username, password).ok()) {
            return null;
        }
        return createSession(playerUuid, token.playerName());
    }

    public AuthResult bindAccountFromToken(String loginToken, String username, String password) {
        if (minecraftServer == null) {
            return AuthResult.invalid("server_unavailable", "Server unavailable");
        }
        MarketWebTokenSavedData.TokenEntry token = MarketWebTokenSavedData.get(minecraftServer.overworld()).resolveToken(loginToken);
        if (token == null) {
            return AuthResult.invalid("invalid_token", "Invalid token");
        }
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(token.playerUuid());
        } catch (IllegalArgumentException exception) {
            return AuthResult.invalid("invalid_token", "Invalid token");
        }
        AccountBindResult bind = bindAccount(playerUuid, token.playerName(), username, password);
        if (!bind.ok()) {
            return AuthResult.invalid(bind.errorCode(), bind.message());
        }
        SessionToken session = createSession(playerUuid, token.playerName());
        if (session == null) {
            return AuthResult.invalid("session_failed", "Failed to create session");
        }
        return new AuthResult(session, bind.accountUsername(), true, null, null);
    }

    public AuthResult loginWithAccount(String username, String password) {
        if (minecraftServer == null) {
            return AuthResult.invalid("server_unavailable", "Server unavailable");
        }
        String normalizedUsername = MarketWebAccountSavedData.normalizeUsername(username);
        if (!isValidUsername(normalizedUsername)) {
            return AuthResult.invalid("invalid_username", "Username must be 3-24 characters using letters, numbers, or underscore");
        }
        if (password == null || password.isBlank()) {
            return AuthResult.invalid("invalid_password", "Password is required");
        }
        MarketWebAccountSavedData.AccountEntry account = MarketWebAccountSavedData.get(minecraftServer.overworld()).getByUsername(normalizedUsername);
        if (account == null) {
            return AuthResult.invalid("account_not_found", "Account not found");
        }
        if (!matchesPassword(password, account.salt(), account.passwordHash())) {
            return AuthResult.invalid("invalid_credentials", "Invalid username or password");
        }
        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(account.playerUuid());
        } catch (IllegalArgumentException exception) {
            return AuthResult.invalid("account_corrupt", "Account data is invalid");
        }
        SessionToken session = createSession(playerUuid, account.playerName());
        if (session == null) {
            return AuthResult.invalid("session_failed", "Failed to create session");
        }
        return new AuthResult(session, account.username(), false, null, null);
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

    public MarketWebAccountSavedData.AccountEntry accountByPlayerUuid(UUID playerUuid) {
        if (playerUuid == null || minecraftServer == null) {
            return null;
        }
        return MarketWebAccountSavedData.get(minecraftServer.overworld()).getByPlayerUuid(playerUuid.toString());
    }

    public void clear() {
        sessions.clear();
    }

    private AccountBindResult bindAccount(UUID playerUuid, String playerName, String username, String password) {
        String normalizedUsername = MarketWebAccountSavedData.normalizeUsername(username);
        if (!isValidUsername(normalizedUsername)) {
            return AccountBindResult.invalid("invalid_username", "Username must be 3-24 characters using letters, numbers, or underscore");
        }
        if (!isValidPassword(password)) {
            return AccountBindResult.invalid("invalid_password", "Password must be 6-72 characters");
        }
        MarketWebAccountSavedData data = MarketWebAccountSavedData.get(minecraftServer.overworld());
        MarketWebAccountSavedData.AccountEntry existingByUsername = data.getByUsername(normalizedUsername);
        if (existingByUsername != null && !existingByUsername.playerUuid().equals(playerUuid.toString())) {
            return AccountBindResult.invalid("username_taken", "Username is already in use");
        }
        MarketWebAccountSavedData.AccountEntry existingByPlayer = data.getByPlayerUuid(playerUuid.toString());
        long now = System.currentTimeMillis();
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = Base64.getEncoder().encodeToString(saltBytes);
        String hash = hashPassword(password, saltBytes);
        if (hash.isBlank()) {
            return AccountBindResult.invalid("password_hash_failed", "Failed to secure password");
        }
        MarketWebAccountSavedData.AccountEntry entry = new MarketWebAccountSavedData.AccountEntry(
                playerUuid.toString(),
                playerName,
                normalizedUsername,
                salt,
                hash,
                existingByPlayer == null ? now : existingByPlayer.createdAtMs(),
                now
        );
        data.upsert(entry);
        return new AccountBindResult(true, entry.username(), null, null);
    }

    private SessionToken createSession(UUID playerUuid, String playerName) {
        if (playerUuid == null) {
            return null;
        }
        String sessionId = randomToken();
        SessionToken session = new SessionToken(
                sessionId,
                playerUuid,
                playerName == null ? "" : playerName.trim(),
                System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L
        );
        sessions.put(sessionId, session);
        return session;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean isValidUsername(String username) {
        return username != null && username.matches("[a-z0-9_]{3,24}");
    }

    private static boolean isValidPassword(String password) {
        return password != null && password.length() >= 6 && password.length() <= 72;
    }

    private static String hashPassword(String password, byte[] saltBytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(encoded);
        } catch (GeneralSecurityException exception) {
            return "";
        }
    }

    private static boolean matchesPassword(String password, String saltBase64, String expectedHash) {
        if (password == null || password.isBlank() || saltBase64 == null || saltBase64.isBlank() || expectedHash == null || expectedHash.isBlank()) {
            return false;
        }
        byte[] saltBytes;
        try {
            saltBytes = Base64.getDecoder().decode(saltBase64);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String actualHash = hashPassword(password, saltBytes);
        if (actualHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    public record SessionToken(String token, UUID playerUuid, String playerName, long expiresAtMs) {
    }

    public record AuthResult(SessionToken session, String accountUsername, boolean newlyBound, String errorCode, String message) {
        public static AuthResult invalid(String errorCode, String message) {
            return new AuthResult(null, "", false, errorCode, message);
        }

        public boolean ok() {
            return session != null;
        }
    }

    private record AccountBindResult(boolean ok, String accountUsername, String errorCode, String message) {
        private static AccountBindResult invalid(String errorCode, String message) {
            return new AccountBindResult(false, "", errorCode, message);
        }
    }
}
