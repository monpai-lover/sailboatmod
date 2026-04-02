package com.monpai.sailboatmod.client;

import net.minecraftforge.fml.ModList;

public final class ModernUiCompat {
    public static final String MOD_ID = "modernui";

    private ModernUiCompat() {
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded(MOD_ID);
    }
}
