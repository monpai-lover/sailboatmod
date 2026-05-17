package com.monpai.sailboatmod.client;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.client.screen.nation.NationTradeScreen;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class TradeClientHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static TradeScreenData lastSyncedData = TradeScreenData.empty();
    private static long closedAtMillis = 0;
    private static final long REOPEN_COOLDOWN_MS = 1500;

    public static void openOrUpdate(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        LOGGER.info("[NationTradeUI] client openOrUpdate ourNation={} targetNation={} screen={}",
                lastSyncedData.ourNationId(),
                lastSyncedData.targetNationId(),
                minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());

        if (minecraft.screen instanceof NationTradeScreen tradeScreen) {
            tradeScreen.updateData(lastSyncedData);
            return;
        }
        if (System.currentTimeMillis() - closedAtMillis < REOPEN_COOLDOWN_MS) {
            LOGGER.info("[NationTradeUI] client suppressed reopen by cooldown targetNation={}",
                    lastSyncedData.targetNationId());
            return;
        }
        LOGGER.info("[NationTradeUI] client opening NationTradeScreen previousScreen={}",
                minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
        try {
            minecraft.setScreen(new NationTradeScreen(lastSyncedData));
        } catch (RuntimeException e) {
            LOGGER.error("[NationTradeUI] failed to open trade screen targetNation={}",
                    lastSyncedData.targetNationId(), e);
        }
    }

    public static void updateIfOpen(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NationTradeScreen tradeScreen) {
            tradeScreen.updateData(lastSyncedData);
        }
    }

    public static void onScreenClosed() {
        closedAtMillis = System.currentTimeMillis();
    }

    public static TradeScreenData lastSyncedData() {
        return lastSyncedData;
    }

    public static void clearCache() {
        lastSyncedData = TradeScreenData.empty();
    }

    public static boolean isTradeScreenActive() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.screen instanceof NationTradeScreen;
    }

    private TradeClientHooks() {
    }
}
