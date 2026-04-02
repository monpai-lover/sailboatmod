package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.town.TownHomeScreen;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import net.minecraft.client.Minecraft;

public final class TownClientHooks {
    private static TownOverviewData lastSyncedData = TownOverviewData.empty();
    private static boolean expectingOpen = false;

    public static void requestOpen() {
        expectingOpen = true;
    }

    public static void openOrUpdate(TownOverviewData data) {
        lastSyncedData = data == null ? TownOverviewData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
            return;
        }
        if (expectingOpen) {
            expectingOpen = false;
            minecraft.setScreen(new TownHomeScreen(lastSyncedData));
        }
    }

    public static void clearCache() {
        lastSyncedData = TownOverviewData.empty();
        expectingOpen = false;
    }

    private TownClientHooks() {
    }
}
