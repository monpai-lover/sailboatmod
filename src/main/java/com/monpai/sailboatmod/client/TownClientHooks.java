package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.town.TownHomeScreen;
import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import net.minecraft.client.Minecraft;

public final class TownClientHooks {
    private static TownOverviewData lastSyncedData = TownOverviewData.empty();
    private static boolean suppressReopen = false;

    public static void requestOpen() {
        suppressReopen = false;
    }

    public static void openCachedOrEmpty() {
        suppressReopen = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
            return;
        }
        minecraft.setScreen(new TownHomeScreen(lastSyncedData));
    }

    public static void openOrUpdate(TownOverviewData data) {
        lastSyncedData = data == null ? TownOverviewData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
            return;
        }
        if (suppressReopen) {
            return;
        }
        minecraft.setScreen(new TownHomeScreen(lastSyncedData));
    }

    public static void onScreenClosed() {
        suppressReopen = true;
    }

    public static void clearCache() {
        lastSyncedData = TownOverviewData.empty();
    }

    public static TownOverviewData lastSyncedData() {
        return lastSyncedData;
    }

    private TownClientHooks() {
    }
}
