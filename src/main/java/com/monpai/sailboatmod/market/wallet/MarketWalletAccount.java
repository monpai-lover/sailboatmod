package com.monpai.sailboatmod.market.wallet;

public record MarketWalletAccount(
        String playerUuid,
        String playerName,
        long availableBalance,
        long reservedBalance,
        long updatedAtMillis
) {
    public MarketWalletAccount {
        playerUuid = playerUuid == null ? "" : playerUuid.trim();
        playerName = playerName == null ? "" : playerName;
        availableBalance = Math.max(0L, availableBalance);
        reservedBalance = Math.max(0L, reservedBalance);
        updatedAtMillis = Math.max(0L, updatedAtMillis);
    }

    public static MarketWalletAccount empty(String playerUuid, String playerName, long nowMillis) {
        return new MarketWalletAccount(playerUuid, playerName, 0L, 0L, nowMillis);
    }

    public long totalBalance() {
        return availableBalance + reservedBalance;
    }
}
