package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.nation.menu.TradeScreenData;

public interface TradeOverviewConsumer {
    boolean isForTrade(String targetNationId);

    void updateData(TradeScreenData data);
}
