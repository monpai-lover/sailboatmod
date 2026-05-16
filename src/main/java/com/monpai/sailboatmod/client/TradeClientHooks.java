package com.monpai.sailboatmod.client;

import com.ldtteam.blockui.BOScreen;
import com.monpai.sailboatmod.client.gui.NationTradeWindow;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import net.minecraft.client.Minecraft;

public final class TradeClientHooks {
    private static TradeScreenData lastSyncedData = TradeScreenData.empty();
    private static long closedAtMillis = 0;
    private static final long REOPEN_COOLDOWN_MS = 1500;

    public static void openOrUpdate(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        NationTradeWindow tradeWindow = currentTradeWindow(minecraft);
        if (tradeWindow != null) {
            tradeWindow.updateData(lastSyncedData);
            return;
        }
        if (System.currentTimeMillis() - closedAtMillis < REOPEN_COOLDOWN_MS) {
            return;
        }
        new NationTradeWindow(lastSyncedData).open();
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

    private static NationTradeWindow currentTradeWindow(Minecraft minecraft) {
        if (minecraft.screen instanceof BOScreen screen && screen.getWindow() instanceof NationTradeWindow tradeWindow) {
            return tradeWindow;
        }
        return null;
    }

    private TradeClientHooks() {
    }
}
