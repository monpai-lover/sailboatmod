package com.monpai.sailboatmod.economy;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public final class VaultEconomyBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(VaultEconomyBridge.class);
    private static final Object LOCK = new Object();
    private static final long RETRY_INTERVAL_MS = 30_000;
    @Nullable
    private static Object economyProvider = null;
    private static boolean providerResolved = false;
    private static boolean bukkitAbsent = false;
    private static long lastAttemptTime = 0;

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
    public static Boolean tryDeposit(Player player, int amount) {
        if (player == null || amount <= 0) {
            return Boolean.TRUE;
        }
        String playerName = player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName();
        return tryDepositByIdentity(player.getUUID(), playerName, amount);
    }

    @Nullable
    public static Boolean tryDepositByIdentity(UUID playerUuid, String playerName, int amount) {
        if (amount <= 0) {
            return Boolean.TRUE;
        }
        Object provider = resolveEconomyProvider();
        if (provider == null) {
            return null;
        }
        try {
            Boolean withOffline = tryDepositWithOfflinePlayer(provider, playerUuid, amount);
            if (withOffline != null) {
                return withOffline;
            }
            return tryDepositWithName(provider, playerName, amount);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private static Object resolveEconomyProvider() {
        if (providerResolved) {
            return economyProvider;
        }
        if (bukkitAbsent) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttemptTime < RETRY_INTERVAL_MS) {
            return economyProvider;
        }
        synchronized (LOCK) {
            if (providerResolved) {
                return economyProvider;
            }
            if (now - lastAttemptTime < RETRY_INTERVAL_MS) {
                return economyProvider;
            }
            lastAttemptTime = now;
            try {
                Class<?> bukkitClass;
                try {
                    bukkitClass = Class.forName("org.bukkit.Bukkit");
                } catch (ClassNotFoundException e) {
                    LOGGER.debug("Bukkit not present, Vault economy disabled");
                    bukkitAbsent = true;
                    return null;
                }

                // On hybrid servers (Mohist/Catserver/Arclight), Vault's classes live
                // in a child PluginClassLoader that the Forge TransformingClassLoader
                // cannot see.  We must load the Economy class through Vault's own
                // classloader, obtained via Bukkit.getPluginManager().getPlugin("Vault").
                Method getPluginManager = bukkitClass.getMethod("getPluginManager");
                Object pluginManager = getPluginManager.invoke(null);
                if (pluginManager == null) {
                    LOGGER.debug("Bukkit PluginManager is null, will retry");
                    return null;
                }
                Method getPlugin = pluginManager.getClass().getMethod("getPlugin", String.class);
                Object vaultPlugin = getPlugin.invoke(pluginManager, "Vault");
                if (vaultPlugin == null) {
                    LOGGER.debug("Vault plugin not found, will retry");
                    return null;
                }
                ClassLoader vaultClassLoader = vaultPlugin.getClass().getClassLoader();

                Class<?> economyClass;
                try {
                    economyClass = Class.forName("net.milkbowl.vault.economy.Economy", true, vaultClassLoader);
                } catch (ClassNotFoundException e) {
                    LOGGER.debug("Vault Economy class not found via Vault classloader, will retry");
                    return null;
                }

                Method getServicesManager = bukkitClass.getMethod("getServicesManager");
                Object servicesManager = getServicesManager.invoke(null);
                if (servicesManager == null) {
                    LOGGER.debug("Bukkit ServicesManager is null, will retry");
                    return null;
                }
                Method getRegistration = servicesManager.getClass().getMethod("getRegistration", Class.class);
                Object registration = getRegistration.invoke(servicesManager, economyClass);
                if (registration == null) {
                    LOGGER.debug("Vault Economy service not registered yet, will retry");
                    return null;
                }
                Method getProvider = registration.getClass().getMethod("getProvider");
                economyProvider = getProvider.invoke(registration);
                if (economyProvider != null) {
                    providerResolved = true;
                    LOGGER.info("Vault economy provider resolved: {}", economyProvider.getClass().getName());
                } else {
                    LOGGER.debug("Vault Economy registration returned null provider, will retry");
                }
                return economyProvider;
            } catch (Throwable e) {
                LOGGER.warn("Failed to resolve Vault economy provider, will retry: {}", e.toString());
                return null;
            }
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

    @Nullable
    private static Boolean tryDepositWithOfflinePlayer(Object provider, UUID playerUuid, int amount) {
        try {
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Class<?> offlinePlayerClass = Class.forName("org.bukkit.OfflinePlayer");
            Method getOfflinePlayer = bukkitClass.getMethod("getOfflinePlayer", UUID.class);
            Object offlinePlayer = getOfflinePlayer.invoke(null, playerUuid);
            Method depositPlayer = provider.getClass().getMethod("depositPlayer", offlinePlayerClass, double.class);
            Object response = depositPlayer.invoke(provider, offlinePlayer, (double) amount);
            return isTransactionSuccess(response);
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Throwable ignored) {
            return Boolean.FALSE;
        }
    }

    @Nullable
    private static Boolean tryDepositWithName(Object provider, String playerName, int amount) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        try {
            Method depositPlayer = provider.getClass().getMethod("depositPlayer", String.class, double.class);
            Object response = depositPlayer.invoke(provider, playerName, (double) amount);
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
