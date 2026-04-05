package com.monpai.sailboatmod.market.web;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MarketWebTokenSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_market_web_tokens";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, TokenEntry> byPlayerUuid = new LinkedHashMap<>();
    private final Map<String, TokenEntry> byToken = new LinkedHashMap<>();

    public static MarketWebTokenSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new MarketWebTokenSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(MarketWebTokenSavedData::load, MarketWebTokenSavedData::new, DATA_NAME);
    }

    public static MarketWebTokenSavedData load(CompoundTag tag) {
        MarketWebTokenSavedData data = new MarketWebTokenSavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            TokenEntry entry = TokenEntry.load(compound);
            if (entry.playerUuid().isBlank() || entry.token().isBlank()) {
                continue;
            }
            data.byPlayerUuid.put(entry.playerUuid(), entry);
            data.byToken.put(entry.token(), entry);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (TokenEntry entry : byPlayerUuid.values()) {
            list.add(entry.save());
        }
        tag.put("Entries", list);
        return tag;
    }

    public String getOrCreateToken(UUID playerUuid, String playerName) {
        if (playerUuid == null) {
            return "";
        }
        String key = playerUuid.toString();
        TokenEntry existing = byPlayerUuid.get(key);
        String safePlayerName = playerName == null ? "" : playerName.trim();
        if (existing != null) {
            if (!safePlayerName.equals(existing.playerName())) {
                TokenEntry updated = new TokenEntry(key, safePlayerName, existing.token());
                byPlayerUuid.put(key, updated);
                byToken.put(updated.token(), updated);
                setDirty();
                return updated.token();
            }
            return existing.token();
        }
        TokenEntry created = new TokenEntry(key, safePlayerName, randomToken());
        byPlayerUuid.put(key, created);
        byToken.put(created.token(), created);
        setDirty();
        return created.token();
    }

    public TokenEntry resolveToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return byToken.get(token.trim());
    }

    public List<TokenEntry> entries() {
        return new ArrayList<>(byPlayerUuid.values());
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record TokenEntry(String playerUuid, String playerName, String token) {
        public TokenEntry {
            playerUuid = playerUuid == null ? "" : playerUuid.trim();
            playerName = playerName == null ? "" : playerName.trim();
            token = token == null ? "" : token.trim();
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("PlayerUuid", playerUuid);
            tag.putString("PlayerName", playerName);
            tag.putString("Token", token);
            return tag;
        }

        public static TokenEntry load(CompoundTag tag) {
            return new TokenEntry(
                    tag.getString("PlayerUuid"),
                    tag.getString("PlayerName"),
                    tag.getString("Token")
            );
        }
    }
}
