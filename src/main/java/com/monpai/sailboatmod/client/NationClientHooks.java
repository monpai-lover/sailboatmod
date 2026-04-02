package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.nation.NationHomeScreen;
import com.monpai.sailboatmod.client.modernui.ModernUiRuntimeBridge;
import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

public final class NationClientHooks {
    private static NationOverviewData lastSyncedData = NationOverviewData.empty();

    public static void openCachedOrEmpty() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
            return;
        }
        if (ModernUiCompat.isAvailable()) {
            ModernUiRuntimeBridge.openNationScreen(lastSyncedData);
            return;
        }
        minecraft.setScreen(new NationHomeScreen(lastSyncedData));
    }

    public static void openOrUpdate(NationOverviewData data) {
        lastSyncedData = data == null ? NationOverviewData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
            return;
        }
        if (ModernUiCompat.isAvailable()) {
            if (!ModernUiRuntimeBridge.updateCurrentNation(lastSyncedData)) {
                ModernUiRuntimeBridge.openNationScreen(lastSyncedData);
            }
            return;
        }
        minecraft.setScreen(new NationHomeScreen(lastSyncedData));
    }

    public static void updateIfOpen(NationOverviewData data) {
        lastSyncedData = data == null ? NationOverviewData.empty() : data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof NationHomeScreen nationHomeScreen) {
            nationHomeScreen.updateData(lastSyncedData);
        } else if (ModernUiCompat.isAvailable()) {
            ModernUiRuntimeBridge.updateCurrentNation(lastSyncedData);
        }
    }

    public static void showToast(String title, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Component titleComponent = Component.literal(title == null || title.isBlank() ? "Nation" : title);
        Component messageComponent = Component.literal(message == null || message.isBlank() ? "-" : message);
        SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION, titleComponent, messageComponent);
    }

    public static NationOverviewData lastSyncedData() {
        return lastSyncedData;
    }

    public static void clearCache() {
        lastSyncedData = NationOverviewData.empty();
    }

    private NationClientHooks() {
    }
}
