package com.monpai.sailboatmod.client;

import com.mojang.logging.LogUtils;
import com.ldtteam.blockui.BOScreen;
import com.monpai.sailboatmod.client.gui.NationTradeWindow;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class TradeClientHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static TradeScreenData lastSyncedData = TradeScreenData.empty();
    private static long closedAtMillis = 0;
    private static final long REOPEN_COOLDOWN_MS = 1500;

    enum OpenMode {
        REPLACE_SCREEN,
        LAYER
    }

    public static void openOrUpdate(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        LOGGER.info("[NationTradeUI] client openOrUpdate ourNation={} targetNation={} screen={}",
                lastSyncedData.ourNationId(),
                lastSyncedData.targetNationId(),
                minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
        NationTradeWindow tradeWindow = currentTradeWindow(minecraft);
        if (tradeWindow != null) {
            tradeWindow.updateData(lastSyncedData);
            return;
        }
        if (System.currentTimeMillis() - closedAtMillis < REOPEN_COOLDOWN_MS) {
            LOGGER.info("[NationTradeUI] client suppressed reopen by cooldown targetNation={}",
                    lastSyncedData.targetNationId());
            return;
        }
        OpenMode mode = openModeForCurrentScreen(minecraft.screen != null);
        LOGGER.info("[NationTradeUI] client constructing window mode={} previousScreen={}", mode, screenName(minecraft));
        try {
            NationTradeWindow window = new NationTradeWindow(lastSyncedData);
            LOGGER.info("[NationTradeUI] client opening window mode={} previousScreen={}", mode, screenName(minecraft));
            if (mode == OpenMode.LAYER) {
                window.openAsLayer();
            } else {
                window.open();
            }
        } catch (RuntimeException e) {
            LOGGER.error("[NationTradeUI] failed to construct/open trade window targetNation={}",
                    lastSyncedData.targetNationId(), e);
        }
        minecraft.submit(() -> LOGGER.info("[NationTradeUI] client after open submit screen={}", screenName(minecraft)));
    }

    public static void updateIfOpen(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        NationTradeWindow tradeWindow = currentTradeWindow(minecraft);
        if (tradeWindow != null) {
            tradeWindow.updateData(lastSyncedData);
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

    static OpenMode openModeForCurrentScreen(boolean hasCurrentScreen) {
        return hasCurrentScreen ? OpenMode.LAYER : OpenMode.REPLACE_SCREEN;
    }

    static boolean isTradeScreenActive(Minecraft minecraft) {
        return currentTradeWindow(minecraft) != null;
    }

    private static NationTradeWindow currentTradeWindow(Minecraft minecraft) {
        if (minecraft == null) {
            return null;
        }
        if (minecraft.screen instanceof BOScreen screen && screen.getWindow() instanceof NationTradeWindow tradeWindow) {
            return tradeWindow;
        }
        return null;
    }

    private static String screenName(Minecraft minecraft) {
        if (minecraft == null || minecraft.screen == null) {
            return "none";
        }
        return minecraft.screen.getClass().getName();
    }

    private TradeClientHooks() {
    }
}
