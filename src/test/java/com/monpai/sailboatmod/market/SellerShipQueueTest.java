package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SellerShipQueueTest {
    @Test
    void positionIsOneBasedAndStable() {
        List<String> queue = List.of("ord-1", "ord-2", "ord-3");
        assertEquals(1, SellerShipQueue.positionOf(queue, "ord-1"));
        assertEquals(2, SellerShipQueue.positionOf(queue, "ord-2"));
        assertEquals(0, SellerShipQueue.positionOf(queue, "absent"));
    }

    @Test
    void etaScalesWithPosition() {
        assertEquals(0, SellerShipQueue.etaSeconds(0));
        assertEquals(60, SellerShipQueue.etaSeconds(1));
        assertEquals(180, SellerShipQueue.etaSeconds(3));
    }
}
