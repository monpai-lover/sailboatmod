package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.PostStationScreen;
import com.monpai.sailboatmod.dock.PostStationScreenData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class PostStationClientHooks {
    private static PostStationScreenData latest;

    public static void openOrUpdate(PostStationScreenData data) {
        latest = data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PostStationScreen screen && screen.isForStation(data.stationPos())) {
            screen.updateData(data);
        }
    }

    public static PostStationScreenData consumeFor(BlockPos pos) {
        if (latest != null && latest.stationPos().equals(pos)) {
            PostStationScreenData out = latest;
            latest = null;
            return out;
        }
        return null;
    }

    private PostStationClientHooks() {
    }
}
