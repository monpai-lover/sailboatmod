package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.market.wallet.MarketWalletService;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * @param returnCargo true 把未发货订单的保留库存退回挂单 availableCount；
     *                    false 不碰挂单库存，实体货物已退仓或仍由运输层保留。
     */
    public static boolean refundAndReleaseOrder(Level level, MarketSavedData market, PurchaseOrder order,
                                             String reason, boolean returnCargo) {
        if (level == null || level.isClientSide() || market == null || order == null) {
            return false;
        }
        order = market.getPurchaseOrder(order.orderId());
        if (order == null) {
            return false;
        }
        if (PurchaseOrder.STATUS_CANCELLED.equals(order.status())) {
            return true; // 查持久化当前状态，旧订单快照也不能重复退款。
        }
        if (!canRefundOrders(level, market, List.of(order))) {
            return false;
        }
        MarketOrderEscrow escrow = market.getEscrow(order.orderId());
        int refundAmount = escrow == null ? order.totalPrice() : escrow.totalPrice();
        if (escrow == null && refundAmount > 0) {
            MarketListing sourceListing = market.getListing(order.listingId());
            if (!MarketWalletService.withdraw(level, sourceListing.sellerUuid(), sourceListing.sellerName(), refundAmount).success()) {
                return false;
            }
        } else if (escrow != null) {
            market.removeEscrow(order.orderId());
        }
        if (refundAmount > 0) {
            MarketWalletService.deposit(level, order.buyerUuid(), order.buyerName(), refundAmount);
        }
        MarketListing listing = returnCargo ? market.getListing(order.listingId()) : null;
        if (listing != null) {
            market.putListing(new MarketListing(
                    listing.listingId(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.itemStack(),
                    listing.unitPrice(),
                    // 无限库存:availableCount 是哨兵,不 +quantity(保持哨兵纯净);否则正常退回库存。
                    listing.isInfinite() ? listing.availableCount() : listing.availableCount() + order.quantity(),
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
                order.orderId(), reason, refundAmount, order.buyerUuid());
        return true;
    }

    /** 检查整个取消批次，防止先退货或退部分订单后才发现旧订单资金无法追回。 */
    public static boolean canRefundOrders(Level level, MarketSavedData market, List<PurchaseOrder> orders) {
        if (level == null || level.isClientSide() || market == null || orders == null) {
            return false;
        }
        Map<String, Long> recoveries = new LinkedHashMap<>();
        Map<String, Long> refunds = new LinkedHashMap<>();
        for (PurchaseOrder snapshot : orders) {
            PurchaseOrder order = snapshot == null ? null : market.getPurchaseOrder(snapshot.orderId());
            if (order == null) {
                return false;
            }
            if (PurchaseOrder.STATUS_CANCELLED.equals(order.status())) {
                continue;
            }
            if ("CLAIMED".equals(order.status()) || "ARRIVED".equals(order.status()) || "DELIVERED".equals(order.status())) {
                return false;
            }
            MarketOrderEscrow escrow = market.getEscrow(order.orderId());
            int amount = escrow == null ? order.totalPrice() : escrow.totalPrice();
            if (amount <= 0) {
                continue;
            }
            if (order.buyerUuid().isBlank()) {
                return false;
            }
            refunds.merge(order.buyerUuid(), (long) amount, Long::sum);
            if (escrow == null) {
                MarketListing listing = market.getListing(order.listingId());
                if (listing == null || listing.sellerUuid().isBlank()) {
                    return false;
                }
                recoveries.merge(listing.sellerUuid(), (long) amount, Long::sum);
            }
        }
        for (Map.Entry<String, Long> recovery : recoveries.entrySet()) {
            if (MarketWalletService.availableBalance(level, recovery.getKey(), "") < recovery.getValue()) {
                return false;
            }
        }
        for (Map.Entry<String, Long> refund : refunds.entrySet()) {
            if (MarketWalletService.availableBalance(level, refund.getKey(), "") > Long.MAX_VALUE - refund.getValue()) {
                return false;
            }
        }
        return true;
    }
}
