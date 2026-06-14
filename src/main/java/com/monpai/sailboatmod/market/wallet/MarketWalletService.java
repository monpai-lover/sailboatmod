package com.monpai.sailboatmod.market.wallet;

import net.minecraft.world.level.Level;

public final class MarketWalletService {
    public record AccountResult(boolean success, MarketWalletAccount account, long amount) {
        static AccountResult success(MarketWalletAccount account, long amount) {
            return new AccountResult(true, account, Math.max(0L, amount));
        }

        static AccountResult failure(MarketWalletAccount account, long amount) {
            return new AccountResult(false, account, Math.max(0L, amount));
        }
    }

    private MarketWalletService() {
    }

    public static MarketWalletAccount getAccount(Level level, String playerUuid, String playerName) {
        return MarketWalletSavedData.get(level).getAccount(playerUuid, playerName);
    }

    public static long availableBalance(Level level, String playerUuid, String playerName) {
        return getAccount(level, playerUuid, playerName).availableBalance();
    }

    public static long reservedBalance(Level level, String playerUuid, String playerName) {
        return getAccount(level, playerUuid, playerName).reservedBalance();
    }

    public static MarketWalletAccount deposit(Level level, String playerUuid, String playerName, long amount) {
        return MarketWalletSavedData.get(level).putAccount(deposit(getAccount(level, playerUuid, playerName), amount, System.currentTimeMillis()));
    }

    public static AccountResult withdraw(Level level, String playerUuid, String playerName, long amount) {
        MarketWalletSavedData data = MarketWalletSavedData.get(level);
        AccountResult result = withdraw(data.getAccount(playerUuid, playerName), amount, System.currentTimeMillis());
        if (result.success()) {
            data.putAccount(result.account());
        }
        return result;
    }

    public static AccountResult reserve(Level level, String playerUuid, String playerName, long amount) {
        MarketWalletSavedData data = MarketWalletSavedData.get(level);
        AccountResult result = reserve(data.getAccount(playerUuid, playerName), amount, System.currentTimeMillis());
        if (result.success()) {
            data.putAccount(result.account());
        }
        return result;
    }

    public static AccountResult releaseReserved(Level level, String playerUuid, String playerName, long amount) {
        MarketWalletSavedData data = MarketWalletSavedData.get(level);
        AccountResult result = releaseReserved(data.getAccount(playerUuid, playerName), amount, System.currentTimeMillis());
        if (result.success()) {
            data.putAccount(result.account());
        }
        return result;
    }

    public static AccountResult spendReserved(Level level, String playerUuid, String playerName, long amount) {
        MarketWalletSavedData data = MarketWalletSavedData.get(level);
        AccountResult result = spendReserved(data.getAccount(playerUuid, playerName), amount, System.currentTimeMillis());
        if (result.success()) {
            data.putAccount(result.account());
        }
        return result;
    }

    public static MarketWalletAccount deposit(MarketWalletAccount account, long amount, long nowMillis) {
        MarketWalletAccount safe = account == null ? MarketWalletAccount.empty("", "", nowMillis) : account;
        long safeAmount = Math.max(0L, amount);
        return new MarketWalletAccount(
                safe.playerUuid(),
                safe.playerName(),
                saturatedAdd(safe.availableBalance(), safeAmount),
                safe.reservedBalance(),
                nowMillis
        );
    }

    public static AccountResult withdraw(MarketWalletAccount account, long amount, long nowMillis) {
        MarketWalletAccount safe = account == null ? MarketWalletAccount.empty("", "", nowMillis) : account;
        long safeAmount = Math.max(0L, amount);
        if (safeAmount <= 0L) {
            return AccountResult.success(new MarketWalletAccount(
                    safe.playerUuid(),
                    safe.playerName(),
                    safe.availableBalance(),
                    safe.reservedBalance(),
                    nowMillis
            ), 0L);
        }
        if (safe.availableBalance() < safeAmount) {
            return AccountResult.failure(safe, safeAmount);
        }
        return AccountResult.success(new MarketWalletAccount(
                safe.playerUuid(),
                safe.playerName(),
                safe.availableBalance() - safeAmount,
                safe.reservedBalance(),
                nowMillis
        ), safeAmount);
    }

    public static AccountResult reserve(MarketWalletAccount account, long amount, long nowMillis) {
        MarketWalletAccount safe = account == null ? MarketWalletAccount.empty("", "", nowMillis) : account;
        long safeAmount = Math.max(0L, amount);
        if (safeAmount <= 0L) {
            return AccountResult.success(new MarketWalletAccount(
                    safe.playerUuid(),
                    safe.playerName(),
                    safe.availableBalance(),
                    safe.reservedBalance(),
                    nowMillis
            ), 0L);
        }
        if (safe.availableBalance() < safeAmount) {
            return AccountResult.failure(safe, safeAmount);
        }
        return AccountResult.success(new MarketWalletAccount(
                safe.playerUuid(),
                safe.playerName(),
                safe.availableBalance() - safeAmount,
                saturatedAdd(safe.reservedBalance(), safeAmount),
                nowMillis
        ), safeAmount);
    }

    public static AccountResult releaseReserved(MarketWalletAccount account, long amount, long nowMillis) {
        MarketWalletAccount safe = account == null ? MarketWalletAccount.empty("", "", nowMillis) : account;
        long safeAmount = Math.max(0L, amount);
        if (safeAmount <= 0L) {
            return AccountResult.success(new MarketWalletAccount(
                    safe.playerUuid(),
                    safe.playerName(),
                    safe.availableBalance(),
                    safe.reservedBalance(),
                    nowMillis
            ), 0L);
        }
        if (safe.reservedBalance() < safeAmount) {
            return AccountResult.failure(safe, safeAmount);
        }
        return AccountResult.success(new MarketWalletAccount(
                safe.playerUuid(),
                safe.playerName(),
                saturatedAdd(safe.availableBalance(), safeAmount),
                safe.reservedBalance() - safeAmount,
                nowMillis
        ), safeAmount);
    }

    public static AccountResult spendReserved(MarketWalletAccount account, long amount, long nowMillis) {
        MarketWalletAccount safe = account == null ? MarketWalletAccount.empty("", "", nowMillis) : account;
        long safeAmount = Math.max(0L, amount);
        if (safeAmount <= 0L) {
            return AccountResult.success(new MarketWalletAccount(
                    safe.playerUuid(),
                    safe.playerName(),
                    safe.availableBalance(),
                    safe.reservedBalance(),
                    nowMillis
            ), 0L);
        }
        if (safe.reservedBalance() < safeAmount) {
            return AccountResult.failure(safe, safeAmount);
        }
        return AccountResult.success(new MarketWalletAccount(
                safe.playerUuid(),
                safe.playerName(),
                safe.availableBalance(),
                safe.reservedBalance() - safeAmount,
                nowMillis
        ), safeAmount);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
