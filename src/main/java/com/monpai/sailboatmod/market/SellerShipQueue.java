package com.monpai.sailboatmod.market;

import java.util.List;

/** 卖家发货排队的纯算法：位次（1-based，缺席 0）与粗估 ETA。无 MC 依赖，便于单测。 */
public final class SellerShipQueue {
    public static final int AVG_DISPATCH_SECONDS_PER_ORDER = 60;

    private SellerShipQueue() {
    }

    public static int positionOf(List<String> orderedQueueIds, String orderId) {
        if (orderedQueueIds == null || orderId == null) {
            return 0;
        }
        int idx = orderedQueueIds.indexOf(orderId);
        return idx < 0 ? 0 : idx + 1;
    }

    public static int etaSeconds(int position) {
        return position <= 0 ? 0 : position * AVG_DISPATCH_SECONDS_PER_ORDER;
    }
}
