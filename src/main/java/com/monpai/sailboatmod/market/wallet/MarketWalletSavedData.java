package com.monpai.sailboatmod.market.wallet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MarketWalletSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_market_wallets";

    private final Map<String, MarketWalletAccount> accounts = new LinkedHashMap<>();

    public static MarketWalletSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new MarketWalletSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(MarketWalletSavedData::load, MarketWalletSavedData::new, DATA_NAME);
    }

    public static MarketWalletSavedData load(CompoundTag tag) {
        MarketWalletSavedData data = new MarketWalletSavedData();
        ListTag accountsTag = tag.getList("Accounts", Tag.TAG_COMPOUND);
        for (Tag raw : accountsTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            MarketWalletAccount account = new MarketWalletAccount(
                    compound.getString("PlayerUuid"),
                    compound.getString("PlayerName"),
                    compound.getLong("AvailableBalance"),
                    compound.getLong("ReservedBalance"),
                    compound.getLong("UpdatedAt")
            );
            if (!account.playerUuid().isBlank()) {
                data.accounts.put(account.playerUuid(), account);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag accountsTag = new ListTag();
        for (MarketWalletAccount account : accounts.values()) {
            CompoundTag compound = new CompoundTag();
            compound.putString("PlayerUuid", account.playerUuid());
            compound.putString("PlayerName", account.playerName());
            compound.putLong("AvailableBalance", account.availableBalance());
            compound.putLong("ReservedBalance", account.reservedBalance());
            compound.putLong("UpdatedAt", account.updatedAtMillis());
            accountsTag.add(compound);
        }
        tag.put("Accounts", accountsTag);
        return tag;
    }

    public MarketWalletAccount getAccount(String playerUuid, String playerName) {
        String key = normalizeUuid(playerUuid);
        if (key.isBlank()) {
            return MarketWalletAccount.empty("", playerName, System.currentTimeMillis());
        }
        MarketWalletAccount account = accounts.get(key);
        if (account != null) {
            return account;
        }
        return MarketWalletAccount.empty(key, playerName, System.currentTimeMillis());
    }

    public MarketWalletAccount putAccount(MarketWalletAccount account) {
        if (account == null || account.playerUuid().isBlank()) {
            return account;
        }
        accounts.put(account.playerUuid(), account);
        setDirty();
        return account;
    }

    private static String normalizeUuid(String playerUuid) {
        return playerUuid == null ? "" : playerUuid.trim();
    }
}
