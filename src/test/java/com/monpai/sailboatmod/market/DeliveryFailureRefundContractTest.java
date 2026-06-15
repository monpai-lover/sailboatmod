package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryFailureRefundContractTest {
    @Test
    void deliveryFailureBranchesRefund() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java"));
        int idx = src.indexOf("private boolean tryDeliverManifestEntryToWarehouse(");
        int end = src.indexOf("\n    private ", idx + 1);
        if (end < 0) {
            end = src.length();
        }
        String body = src.substring(idx, end);
        // 仓不存在分支退款
        assertTrue(body.contains("delivery_failed_no_warehouse"),
                "no-warehouse failure should refund the buyer");
        // 入仓失败分支退款
        assertTrue(body.contains("delivery_failed_full"),
                "warehouse-full failure should refund the buyer");
        // 走共享退款服务
        assertTrue(body.contains("MarketRefundService.refundAndReleaseOrder("),
                "delivery failure should use the shared refund service");
    }

    @Test
    void refundServiceIsIdempotentAndShared() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/market/MarketRefundService.java"));
        assertTrue(src.contains("STATUS_CANCELLED.equals(order.status())"),
                "shared refund service should be idempotent");
        assertTrue(src.contains("MarketWalletService.deposit(level, order.buyerUuid(), order.buyerName(), order.totalPrice())"),
                "shared refund service should deposit full totalPrice");
    }
}
