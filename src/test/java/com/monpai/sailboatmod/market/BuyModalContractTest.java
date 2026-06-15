package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuyModalContractTest {
    private static String marketScreen() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/screen/MarketScreen.java"));
    }

    @Test
    void sendBuyUsesFiveArgPacket() throws Exception {
        String src = marketScreen();
        int idx = src.indexOf("private void sendBuy()");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        assertTrue(body.contains("buyFulfillment"),
                "sendBuy should pass selected fulfillment mode");
        assertTrue(body.contains("buyReceivingPos"),
                "sendBuy should pass chosen receiving warehouse pos");
    }

    @Test
    void footerOpensModal() throws Exception {
        String src = marketScreen();
        assertTrue(src.contains("openBuyModal()"),
                "footer buy should open the modal, not send directly");
    }
}
