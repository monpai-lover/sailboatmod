package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.market.MarketOverviewData;
import net.minecraft.core.BlockPos;

public interface MarketOverviewConsumer {
    boolean isForMarket(BlockPos pos);

    void updateData(MarketOverviewData data);
}
