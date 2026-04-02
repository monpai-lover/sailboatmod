package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.nation.TradeScreen;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import net.minecraft.client.Minecraft;

public final class TradeClientHooks {
    private static TradeScreenData lastSyncedData = TradeScreenData.empty();
    private static long closedAtMillis = 0;
    private static final long REOPEN_COOLDOWN_MS = 1500;

    public static void openOrUpdate(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TradeScreen tradeScreen) {
            tradeScreen.updateData(lastSyncedData);
            return;
        }
        if (System.currentTimeMillis() - closedAtMillis < REOPEN_COOLDOWN_MS) {
            return;
        }
        minecraft.setScreen(new TradeScreen(lastSyncedData));
    }

    public static void updateIfOpen(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TradeScreen tradeScreen) {
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

    private TradeClientHooks() {
    }
}
