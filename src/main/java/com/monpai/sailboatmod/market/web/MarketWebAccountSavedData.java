package com.monpai.sailboatmod.market.web;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MarketWebAccountSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_market_web_accounts";

    private final Map<String, AccountEntry> byUsername = new LinkedHashMap<>();
    private final Map<String, AccountEntry> byPlayerUuid = new LinkedHashMap<>();

    public static MarketWebAccountSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new MarketWebAccountSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(MarketWebAccountSavedData::load, MarketWebAccountSavedData::new, DATA_NAME);
    }

    public static MarketWebAccountSavedData load(CompoundTag tag) {
        MarketWebAccountSavedData data = new MarketWebAccountSavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            AccountEntry entry = AccountEntry.load(compound);
            if (entry.playerUuid().isBlank() || entry.username().isBlank()) {
                continue;
            }
            data.byUsername.put(entry.username(), entry);
            data.byPlayerUuid.put(entry.playerUuid(), entry);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (AccountEntry entry : byPlayerUuid.values()) {
            list.add(entry.save());
        }
        tag.put("Entries", list);
        return tag;
    }

    public AccountEntry getByPlayerUuid(String playerUuid) {
        if (playerUuid == null || playerUuid.isBlank()) {
            return null;
        }
        return byPlayerUuid.get(playerUuid.trim());
    }

    public AccountEntry getByUsername(String username) {
        String normalized = normalizeUsername(username);
        if (normalized.isBlank()) {
            return null;
        }
        return byUsername.get(normalized);
    }

    public AccountEntry upsert(AccountEntry entry) {
        if (entry == null || entry.playerUuid().isBlank() || entry.username().isBlank()) {
            return null;
        }
        AccountEntry previousByPlayer = byPlayerUuid.get(entry.playerUuid());
        if (previousByPlayer != null && !previousByPlayer.username().equals(entry.username())) {
            byUsername.remove(previousByPlayer.username());
        }
        AccountEntry previousByUsername = byUsername.get(entry.username());
        if (previousByUsername != null && !previousByUsername.playerUuid().equals(entry.playerUuid())) {
            byPlayerUuid.remove(previousByUsername.playerUuid());
        }
        byPlayerUuid.put(entry.playerUuid(), entry);
        byUsername.put(entry.username(), entry);
        setDirty();
        return entry;
    }

    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    public record AccountEntry(
            String playerUuid,
            String playerName,
            String username,
            String salt,
            String passwordHash,
            long createdAtMs,
            long updatedAtMs
    ) {
        public AccountEntry {
            playerUuid = playerUuid == null ? "" : playerUuid.trim();
            playerName = playerName == null ? "" : playerName.trim();
            username = normalizeUsername(username);
            salt = salt == null ? "" : salt.trim();
            passwordHash = passwordHash == null ? "" : passwordHash.trim();
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("PlayerUuid", playerUuid);
            tag.putString("PlayerName", playerName);
            tag.putString("Username", username);
            tag.putString("Salt", salt);
            tag.putString("PasswordHash", passwordHash);
            tag.putLong("CreatedAtMs", createdAtMs);
            tag.putLong("UpdatedAtMs", updatedAtMs);
            return tag;
        }

        public static AccountEntry load(CompoundTag tag) {
            return new AccountEntry(
                    tag.getString("PlayerUuid"),
                    tag.getString("PlayerName"),
                    tag.getString("Username"),
                    tag.getString("Salt"),
                    tag.getString("PasswordHash"),
                    tag.getLong("CreatedAtMs"),
                    tag.getLong("UpdatedAtMs")
            );
        }
    }
}
