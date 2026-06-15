package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * P2P 货运订单退款的纯服务：退款全额 totalPrice 给买家，可选归还货物到挂单，订单标记 CANCELLED。
 * 由市场（买家主动取消 / 卖家撤单）与运输层（送达失败）共用，避免重复逻辑。
 */
public final class MarketRefundService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MarketRefundService() {
    }

    /**
     * 退款并（可选）归还一个订单的货物。幂等：已 CANCELLED 的订单直接跳过，不重复退款。
     *
     * @param returnCargo true（买家取消/送达失败）把本单 reservedCount 退回挂单 availableCount；
     *                    false（卖家撤单）不碰挂单——货物随撤单已回卖家仓，只退钱。
     */
    public static void refundAndReleaseOrder(Level level, MarketSavedData market, PurchaseOrder order,
                                             String reason, boolean returnCargo) {
        if (level == null || level.isClientSide() || market == null || order == null) {
            return;
        }
        if (PurchaseOrder.STATUS_CANCELLED.equals(order.status())) {
            return; // 幂等守卫
        }
        if (order.totalPrice() > 0 && order.buyerUuid() != null && !order.buyerUuid().isBlank()) {
            MarketWalletService.deposit(level, order.buyerUuid(), order.buyerName(), order.totalPrice());
        }
        MarketListing listing = returnCargo ? market.getListing(order.listingId()) : null;
        if (listing != null) {
            market.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    listing.availableCount() + order.quantity(),
                    Math.max(0, listing.reservedCount() - order.quantity()),
                    listing.sourceDockPos(),
                    listing.sourceDockName(),
                    listing.townId(),
                    listing.nationId(),
                    listing.priceAdjustmentBp(),
                    listing.sellerNote()
            ));
        }
        market.putPurchaseOrder(new PurchaseOrder(
                order.orderId(),
                order.listingId(),
                order.buyerUuid(),
                order.buyerName(),
                order.quantity(),
                order.totalPrice(),
                order.sourceDockPos(),
                order.sourceDockName(),
                order.targetDockPos(),
                order.targetDockName(),
                PurchaseOrder.STATUS_CANCELLED,
                order.fulfillment(),
                order.targetWarehousePos()
        ));
        LOGGER.info("Refunded purchase order {} ({}), returned {} to buyer {}",
                order.orderId(), reason, order.totalPrice(), order.buyerUuid());
    }
}
