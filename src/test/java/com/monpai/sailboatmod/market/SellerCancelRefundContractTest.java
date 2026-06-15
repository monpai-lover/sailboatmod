package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellerCancelRefundContractTest {
    @Test
    void cancelListingRefundsStrandedOrders() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        int idx = src.indexOf("public CancelListingResult cancelListingResultById(");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        // 撤单时收集挂单下未发货订单
        assertTrue(body.contains("getActiveOrdersForListing(listing.listingId())"),
                "cancelListing should collect stranded not-yet-shipped orders for refund");
        // 对每个滞留订单退款（卖家撤单，不归还货物 returnCargo=false）
        assertTrue(body.contains("refundAndReleaseOrder(market, stranded, \"seller_cancel\", false)"),
                "cancelListing should refund each stranded order without returning cargo to the (deleted) listing");
    }
}
