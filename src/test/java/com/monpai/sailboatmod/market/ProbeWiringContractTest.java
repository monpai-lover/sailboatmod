package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeWiringContractTest {
    @Test
    void probePacketsRegistered() throws Exception {
        String net = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));
        assertTrue(net.contains("ProbeFulfillmentModesPacket"),
                "request packet should be registered in ModNetwork");
        assertTrue(net.contains("ProbeFulfillmentModesResultPacket"),
                "result packet should be registered in ModNetwork");
    }

    @Test
    void requestPacketCallsProbe() throws Exception {
        String pkt = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesPacket.java"));
        assertTrue(pkt.contains("probeFulfillmentModes("),
                "request handler should call market.probeFulfillmentModes");
    }

    @Test
    void resultPacketForwardsToClientHook() throws Exception {
        String pkt = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/network/packet/ProbeFulfillmentModesResultPacket.java"));
        assertTrue(pkt.contains("MarketClientHooks.applyProbeResult("),
                "result handler should forward to MarketClientHooks.applyProbeResult");
    }
}
