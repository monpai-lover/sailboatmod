package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundCoreContractTest {
    private static String marketBlockEntity() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void refundCoreExistsAndRefundsFullAmount() throws Exception {
        String src = marketBlockEntity();
        int idx = src.indexOf("private void refundAndReleaseOrder(");
        assertTrue(idx >= 0, "refundAndReleaseOrder should exist");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        // 幂等守卫：已 CANCELLED 直接返回
        assertTrue(body.contains("STATUS_CANCELLED.equals(order.status())"),
                "refund should be idempotent - skip already-cancelled orders");
        // 全额退款给买家
        assertTrue(body.contains("MarketWalletService.deposit(level, order.buyerUuid(), order.buyerName(), order.totalPrice())"),
                "refund should deposit full totalPrice back to buyer");
        // 货物归还：reserved 退回 available
        assertTrue(body.contains("listing.availableCount() + order.quantity()")
                        && body.contains("listing.reservedCount() - order.quantity()"),
                "refund should return reserved cargo to available when listing still exists");
        // 订单标记 CANCELLED
        assertTrue(body.contains("PurchaseOrder.STATUS_CANCELLED"),
                "refund should mark order CANCELLED");
    }

    @Test
    void buyerCancelGuardsOwnerAndStatus() throws Exception {
        String src = marketBlockEntity();
        int idx = src.indexOf("public CancelPurchaseResult cancelPurchaseOrderById(");
        assertTrue(idx >= 0, "cancelPurchaseOrderById should exist");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        // 仅本人
        assertTrue(body.contains("equals(order.buyerUuid())"),
                "buyer cancel should require requester == buyer");
        // 仅未发货态可取消（含 IN_TRANSIT 拒绝）
        assertTrue(body.contains("\"WAITING_SHIPMENT\".equals(status)")
                        && body.contains("PickupLock.STATUS_LOCKED.equals(status)"),
                "buyer cancel should only allow not-yet-shipped statuses");
        assertTrue(body.contains("failed_in_transit"),
                "buyer cancel should reject in-transit/shipped orders");
    }

    @Test
    void deliveryFailureRefundExists() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("public void refundFailedDelivery("),
                "refundFailedDelivery should exist for delivery-failure refunds");
    }
}
