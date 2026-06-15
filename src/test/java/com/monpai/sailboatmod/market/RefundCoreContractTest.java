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
    void refundCoreDelegatesToSharedService() throws Exception {
        String src = marketBlockEntity();
        int idx = src.indexOf("private void refundAndReleaseOrder(");
        assertTrue(idx >= 0, "refundAndReleaseOrder should exist");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        // 退款核心逻辑统一在 MarketRefundService（DRY），此处委托
        assertTrue(body.contains("MarketRefundService.refundAndReleaseOrder(level, market, order, reason, returnCargo)"),
                "refundAndReleaseOrder should delegate to the shared MarketRefundService");
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
