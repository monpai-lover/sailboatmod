package com.example.examplemod.client;

import com.example.examplemod.client.screen.town.TownHomeScreen;
import com.example.examplemod.nation.menu.TownOverviewData;
import net.minecraft.client.Minecraft;

public final class TownClientHooks {
    private static TownOverviewData lastSyncedData = TownOverviewData.empty();

    public static void openOrUpdate(TownOverviewData data) {
        lastSyncedData = data == null ? TownOverviewData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof TownHomeScreen townHomeScreen) {
            townHomeScreen.updateData(lastSyncedData);
            return;
        }
        minecraft.setScreen(new TownHomeScreen(lastSyncedData));
    }

    public static void clearCache() {
        lastSyncedData = TownOverviewData.empty();
    }

    private TownClientHooks() {
    }
}