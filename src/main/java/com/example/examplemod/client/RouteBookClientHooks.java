package com.example.examplemod.client;

import com.example.examplemod.client.screen.RouteBookNameScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public final class RouteBookClientHooks {
    public static void openNamingScreen(InteractionHand hand, String suggestedName) {
        Minecraft.getInstance().setScreen(new RouteBookNameScreen(hand, suggestedName));
    }

    private RouteBookClientHooks() {
    }
}
