package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.RouteAtlasScreen;

/** Kept outside the common item class so a dedicated server never loads screen classes. */
public final class RouteAtlasClientHooks {
    private RouteAtlasClientHooks() { }
    public static void open() { RouteAtlasScreen.open(); }
}
