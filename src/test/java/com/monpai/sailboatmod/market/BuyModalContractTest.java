package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The market screen moved to LDLib2: the purchase is sent by {@code MarketController.confirmBuy()} and the buy
 * button lives in {@code MarketView}. The product rules are unchanged: Buy opens the delivery dialog instead of
 * sending, and the purchase packet carries the chosen delivery mode and receiving warehouse.
 */
class BuyModalContractTest {
    private static final Path UI = Path.of("src/main/java/com/monpai/sailboatmod/client/ui/market");

    @Test
    void confirmBuyUsesFiveArgPacket() throws Exception {
        String src = Files.readString(UI.resolve("MarketController.java"));
        int idx = src.indexOf("public Result confirmBuy()");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        assertTrue(body.contains("new PurchaseMarketListingPacket("), "confirmBuy should send the purchase packet");
        assertTrue(body.contains("model.buyFulfillment()"), "confirmBuy should pass the selected fulfillment mode");
        assertTrue(body.contains("model.buyReceivingPosOrZero()"), "confirmBuy should pass the chosen receiving warehouse");
    }

    @Test
    void buyButtonOpensDialog() throws Exception {
        String view = Files.readString(UI.resolve("MarketView.java"));
        assertTrue(view.contains("controller::openBuyDialog"), "the Buy button should open the dialog, not send directly");
        String model = Files.readString(UI.resolve("MarketViewModel.java"));
        assertTrue(model.contains("private String buyFulfillment") && model.contains("private BlockPos buyReceivingPos"),
                "the dialog keeps the selected mode and warehouse as UI state");
    }
}
