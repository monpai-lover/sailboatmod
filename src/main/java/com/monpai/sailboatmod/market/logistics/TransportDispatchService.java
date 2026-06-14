package com.monpai.sailboatmod.market.logistics;

import com.monpai.sailboatmod.market.FulfillmentMode;

/** 后台运输调度：定时让需车订单（卖家发货/自动自提）自动发车，无车排队。 */
public final class TransportDispatchService {
    /** 待发状态常量（与 PurchaseOrder 一致）。 */
    public static final String STATUS_WAITING = "WAITING_SHIPMENT";

    private TransportDispatchService() {
    }

    /** 一个订单是否该被后台自动发车：待发状态 + fulfillment 需车（②③，非真人自提）。 */
    public static boolean isBackgroundDispatchable(String status, String fulfillment) {
        if (status == null || fulfillment == null) {
            return false;
        }
        if (!STATUS_WAITING.equals(status.trim())) {
            return false;
        }
        return FulfillmentMode.fromString(fulfillment).needsVehicleDispatch();
    }

    /** 后台调度总入口：遍历各 level 已注册市场，逐个发其待发需车订单。 */
    public static void globalTick(net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.core.BlockPos pos : com.monpai.sailboatmod.market.MarketRegistry.get(level)) {
                if (level.isLoaded(pos)
                        && level.getBlockEntity(pos) instanceof com.monpai.sailboatmod.block.entity.MarketBlockEntity market) {
                    market.runBackgroundAutoDispatch();
                }
            }
        }
    }
}
