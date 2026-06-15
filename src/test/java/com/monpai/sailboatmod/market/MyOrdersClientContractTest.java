package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端「我的订单」分页契约：买家在游戏内市场 GUI 也应看到自己的采购订单详情、
 * 数量/总价、源→目的码头、状态、排队 ETA 与运输摘要，并能取消未发货订单。
 *
 * <p>数据沿 OpenMarketScreenPacket 同步：MarketOverviewData 新增 MyOrderEntry 记录 +
 * myOrders 列表字段，由 MarketBlockEntity 按买家 UUID 填充（与 Web 端 MarketWebService.myOrders 同源）。
 */
class MyOrdersClientContractTest {

    @Test
    void marketOverviewDataHasMyOrdersChannel() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java"));
        assertTrue(src.contains("record MyOrderEntry("),
                "MarketOverviewData should declare a MyOrderEntry record for the buyer's own orders");
        assertTrue(src.contains("List<MyOrderEntry> myOrders"),
                "MarketOverviewData should carry a myOrders list field");
        assertTrue(src.contains("public boolean cancellable()")
                        && src.indexOf("record MyOrderEntry(") < src.indexOf("public boolean cancellable()",
                        src.indexOf("record MyOrderEntry(")),
                "MyOrderEntry should expose a cancellable() helper for un-shipped orders");
    }

    @Test
    void packetEncodesMyOrders() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/network/packet/OpenMarketScreenPacket.java"));
        assertTrue(src.contains("writeMyOrderEntries("),
                "OpenMarketScreenPacket should encode myOrders");
        assertTrue(src.contains("readMyOrderEntries("),
                "OpenMarketScreenPacket should decode myOrders");
    }

    @Test
    void marketBlockEntityPopulatesMyOrders() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        assertTrue(src.contains("getOrdersForBuyer("),
                "MarketBlockEntity should populate myOrders from getOrdersForBuyer");
        assertTrue(src.contains("MyOrderEntry"),
                "MarketBlockEntity should build MyOrderEntry instances");
    }

    @Test
    void marketScreenHasMyOrdersPage() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java"));
        assertTrue(src.contains("MY_ORDERS"),
                "MarketScreen should have a MY_ORDERS market page tab");
        assertTrue(src.contains("buildMyOrdersPage"),
                "MarketScreen should render the my-orders page");
        assertTrue(src.contains("CancelPurchaseOrderPacket"),
                "MarketScreen my-orders page should wire a cancel button via CancelPurchaseOrderPacket");
    }

    @Test
    void langHasMyOrdersKeys() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String key : new String[]{
                "screen.sailboatmod.market.my_orders",
                "screen.sailboatmod.market.my_orders.empty",
                "screen.sailboatmod.market.my_orders.eta",
                "screen.sailboatmod.market.my_orders.cancel"}) {
            assertTrue(zh.contains("\"" + key + "\""), "zh missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en missing " + key);
        }
    }
}
