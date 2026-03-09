package com.example.examplemod.client;

import com.example.examplemod.client.screen.MarketScreen;
import com.example.examplemod.market.MarketOverviewData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class MarketClientHooks {
    private static MarketOverviewData latest;

    public static void openOrUpdate(MarketOverviewData data) {
        latest = data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MarketScreen marketScreen && marketScreen.isForMarket(data.marketPos())) {
            marketScreen.updateData(data);
        }
    }

    public static MarketOverviewData consumeFor(BlockPos pos) {
        if (latest != null && latest.marketPos().equals(pos)) {
            MarketOverviewData out = latest;
            latest = null;
            return out;
        }
        return null;
    }

    private MarketClientHooks() {
    }
}
