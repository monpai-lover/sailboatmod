package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UnloadOnArrivalWiringContractTest {
    private static String carriage() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
    }

    @Test
    void carriageHasUnloadOnArrivalFieldDefaultTrue() throws Exception {
        String src = carriage();
        assertTrue(src.contains("private boolean unloadOnArrival = true"),
                "carriage should have unloadOnArrival defaulting to true (unload by default)");
    }

    @Test
    void carriagePersistsUnloadOnArrivalInNbt() throws Exception {
        String src = carriage();
        assertTrue(src.contains("putBoolean(\"UnloadOnArrival\", unloadOnArrival)"),
                "carriage should write unloadOnArrival to NBT");
        assertTrue(src.contains("getBoolean(\"UnloadOnArrival\")"),
                "carriage should read unloadOnArrival from NBT");
    }

    @Test
    void carriageSetterIsWiredNotEmpty() throws Exception {
        String src = carriage();
        assertTrue(src.contains("this.unloadOnArrival = allow")
                        || src.contains("unloadOnArrival = allow"),
                "setAllowNonOrderAutoUnload must assign the field, not be an empty stub");
    }

    @Test
    void finishAutopilotUsesUnloadDecisionAndFallbackDelivery() throws Exception {
        String src = carriage();
        assertTrue(src.contains("shouldUnloadAtArrival("),
                "finishAutopilot should gate unloading on the unload decision");
        assertTrue(src.contains("hasTransportOrder("),
                "carriage should detect order-driven trips to force unload");
        assertTrue(src.contains("DockBlockEntity.resolveDeliverCargo("),
                "finishAutopilot should use the whole-pool fallback delivery");
    }

    @Test
    void sailboatDefaultsToUnloadAndUsesFallbackDelivery() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        assertTrue(src.contains("autopilotAllowNonOrderAutoUnload = true"),
                "sailboat should default to unload-on-arrival (true)");
        assertTrue(src.contains("DockBlockEntity.resolveDeliverCargo("),
                "sailboat unload should use the whole-pool fallback too");
    }

    @Test
    void unloadTogglePacketIsRegisteredAndWired() throws Exception {
        String packet = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/packet/SetUnloadOnArrivalPacket.java"));
        String network = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));

        assertTrue(packet.contains("public static void encode(")
                        && packet.contains("public static SetUnloadOnArrivalPacket decode(")
                        && packet.contains("public static void handle("),
                "packet must follow encode/decode/handle triple");
        assertTrue(packet.contains("setAllowNonOrderAutoUnload("),
                "packet handle should toggle the vehicle unload switch");
        assertTrue(network.contains("SetUnloadOnArrivalPacket.class"),
                "packet must be registered in ModNetwork");
    }

    @Test
    void infoScreensExposeUnloadToggle() throws Exception {
        String carriageScreen = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/client/screen/CarriageInfoScreen.java"));
        String sailboatScreen = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/client/screen/SailboatInfoScreen.java"));

        assertTrue(carriageScreen.contains("SetUnloadOnArrivalPacket"),
                "carriage info screen should send the unload-toggle packet");
        assertTrue(sailboatScreen.contains("SetUnloadOnArrivalPacket"),
                "sailboat info screen should send the unload-toggle packet");
        assertTrue(carriageScreen.contains("screen.sailboatmod.vehicle.unload_on_arrival"),
                "carriage screen should use the localized unload-toggle label");
    }
}
