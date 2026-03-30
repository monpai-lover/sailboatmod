package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.DockScreen;
import com.monpai.sailboatmod.dock.DockScreenData;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

public final class DockClientHooks {
    private static DockScreenData latest;

    public static void openOrUpdate(DockScreenData data) {
        latest = data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof DockScreen dockScreen && dockScreen.isForDock(data.dockPos())) {
            dockScreen.updateData(data);
        }
    }

    public static DockScreenData consumeFor(BlockPos pos) {
        if (latest != null && latest.dockPos().equals(pos)) {
            DockScreenData out = latest;
            latest = null;
            return out;
        }
        return null;
    }

    private DockClientHooks() {
    }
}
