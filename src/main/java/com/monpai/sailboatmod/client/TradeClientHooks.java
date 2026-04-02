package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.nation.TradeScreen;
import com.monpai.sailboatmod.nation.menu.TradeScreenData;
import net.minecraft.client.Minecraft;

public final class TradeClientHooks {
    private static TradeScreenData lastSyncedData = TradeScreenData.empty();

    public static void openOrUpdate(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TradeScreen tradeScreen) {
            tradeScreen.updateData(lastSyncedData);
        }
        // Do not open a new screen here — only update if already open.
    }

    public static void updateIfOpen(TradeScreenData data) {
        lastSyncedData = data == null ? TradeScreenData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TradeScreen tradeScreen) {
            tradeScreen.updateData(lastSyncedData);
        }
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
