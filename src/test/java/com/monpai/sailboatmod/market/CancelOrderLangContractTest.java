package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelOrderLangContractTest {
    private static final String[] CLIENT_KEYS = {
        "screen.sailboatmod.market.order.cancel",
        "screen.sailboatmod.market.order.cancel.success",
        "screen.sailboatmod.market.order.cancel.failed_missing",
        "screen.sailboatmod.market.order.cancel.failed_not_owner",
        "screen.sailboatmod.market.order.cancel.failed_in_transit",
    };

    @Test
    void clientLangHasCancelKeys() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String key : CLIENT_KEYS) {
            assertTrue(zh.contains("\"" + key + "\""), "zh_cn.json missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en_us.json missing " + key);
        }
    }

    @Test
    void webDictHasOrderCancel() throws Exception {
        String app = Files.readString(Path.of("src/main/resources/marketweb/app.js"));
        assertTrue(app.contains("order_cancel: \"Cancel Order\""), "app.js en dict missing order_cancel");
        assertTrue(app.contains("order_cancel: \"取消订单\""), "app.js zh dict missing order_cancel");
    }
}
