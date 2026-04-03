package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.client.screen.MarketScreen;
import com.monpai.sailboatmod.market.MarketOverviewData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

public final class MarketClientHooks {
    private static MarketOverviewData latest;
    private static MarketNotice latestNotice;

    public static void openOrUpdate(MarketOverviewData data) {
        latest = data;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MarketScreen marketScreen && marketScreen.isForMarket(data.marketPos())) {
            marketScreen.updateData(data);
        }
    }

    public static void showNotice(BlockPos marketPos, String message, boolean positive) {
        latestNotice = new MarketNotice(marketPos, message == null ? "" : message, positive);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MarketScreen marketScreen && marketScreen.isForMarket(marketPos)) {
            marketScreen.showNotice(message, positive);
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

    public static MarketNotice consumeNoticeFor(BlockPos pos) {
        if (latestNotice != null && latestNotice.marketPos().equals(pos)) {
            MarketNotice out = latestNotice;
            latestNotice = null;
            return out;
        }
        return null;
    }

    public record MarketNotice(BlockPos marketPos, String message, boolean positive) {
    }

    private MarketClientHooks() {
    }
}
