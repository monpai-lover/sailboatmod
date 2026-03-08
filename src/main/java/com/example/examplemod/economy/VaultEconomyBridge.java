package com.example.examplemod.economy;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public final class VaultEconomyBridge {
    private static final Object LOCK = new Object();
    @Nullable
    private static Object economyProvider = null;
    private static boolean providerResolved = false;

    private VaultEconomyBridge() {
    }

    @Nullable
    public static Boolean tryWithdraw(Player player, int amount) {
        if (player == null || amount <= 0) {
            return Boolean.TRUE;
        }
        Object provider = resolveEconomyProvider();
        if (provider == null) {
            return null;
        }
        try {
            Boolean withOffline = tryWithdrawWithOfflinePlayer(provider, player, amount);
            if (withOffline != null) {
                return withOffline;
            }
            return tryWithdrawWithName(provider, player, amount);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object resolveEconomyProvider() {
        if (providerResolved) {
            return economyProvider;
        }
        synchronized (LOCK) {
            if (providerResolved) {
                return economyProvider;
            }
            try {
                Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
                Method getServicesManager = bukkitClass.getMethod("getServicesManager");
                Object servicesManager = getServicesManager.invoke(null);
                Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
                Method getRegistration = servicesManager.getClass().getMethod("getRegistration", Class.class);
                Object registration = getRegistration.invoke(servicesManager, economyClass);
                if (registration != null) {
                    Method getProvider = registration.getClass().getMethod("getProvider");
                    economyProvider = getProvider.invoke(registration);
                }
            } catch (Throwable ignored) {
                economyProvider = null;
            }
            providerResolved = true;
            return economyProvider;
        }
    }

    @Nullable
    private static Boolean tryWithdrawWithOfflinePlayer(Object provider, Player player, int amount) {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Class<?> offlinePlayerClass = Class.forName("org.bukkit.OfflinePlayer");
            Method getOfflinePlayer = bukkitClass.getMethod("getOfflinePlayer", UUID.class);
            Object offlinePlayer = getOfflinePlayer.invoke(null, player.getUUID());
            Method withdrawPlayer = provider.getClass().getMethod("withdrawPlayer", offlinePlayerClass, double.class);
            Object response = withdrawPlayer.invoke(provider, offlinePlayer, (double) amount);
            return isTransactionSuccess(response);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable ignored) {
            return Boolean.FALSE;
        }
    }

    @Nullable
    private static Boolean tryWithdrawWithName(Object provider, Player player, int amount) {
        try {
            String playerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
            Method withdrawPlayer = provider.getClass().getMethod("withdrawPlayer", String.class, double.class);
            Object response = withdrawPlayer.invoke(provider, playerName, (double) amount);
            return isTransactionSuccess(response);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable ignored) {
            return Boolean.FALSE;
        }
    }

    private static boolean isTransactionSuccess(@Nullable Object response) {
        if (response == null) {
            return false;
        }
        try {
            Method transactionSuccess = response.getClass().getMethod("transactionSuccess");
            Object result = transactionSuccess.invoke(response);
            if (result instanceof Boolean ok) {
                return ok;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field typeField = response.getClass().getField("type");
            Object typeValue = typeField.get(response);
            return typeValue != null && "SUCCESS".equalsIgnoreCase(typeValue.toString());
        } catch (Throwable ignored) {
        }
        return false;
    }
}
