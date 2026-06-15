package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelOrderWiringContractTest {
    @Test
    void packetRegisteredAndCallsCancel() throws Exception {
        String net = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));
        assertTrue(net.contains("CancelPurchaseOrderPacket"),
                "CancelPurchaseOrderPacket should be registered in ModNetwork");
        String pkt = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/network/packet/CancelPurchaseOrderPacket.java"));
        assertTrue(pkt.contains("cancelPurchaseOrderById(player.getUUID().toString()"),
                "packet handler should call market.cancelPurchaseOrderById with sender uuid");
    }
}
