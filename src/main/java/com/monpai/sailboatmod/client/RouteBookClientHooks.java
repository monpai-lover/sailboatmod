package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.modernui.ModernUiRuntimeBridge;
import com.monpai.sailboatmod.client.screen.RouteBookNameScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public final class RouteBookClientHooks {
    public static void openNamingScreen(InteractionHand hand, String suggestedName) {
        if (ModernUiCompat.isAvailable()) {
            ModernUiRuntimeBridge.openRouteBookNameScreen(hand, suggestedName);
            return;
        }
        Minecraft.getInstance().setScreen(new RouteBookNameScreen(hand, suggestedName));
    }

    private RouteBookClientHooks() {
    }
}
