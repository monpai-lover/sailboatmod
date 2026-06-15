package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostStationAutoUnloadContractTest {
    @Test
    void dispatchUsesConfiguredAutoUnload() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/block/entity/PostStationBlockEntity.java"));
        assertTrue(src.contains("private boolean autoUnloadOnDispatch = true"),
                "post station should have an autoUnloadOnDispatch field defaulting to true");
        assertTrue(src.contains("vehicle.setAllowNonOrderAutoUnload(autoUnloadOnDispatch)"),
                "dispatch should use the configured auto-unload setting, not hardcoded false");
        assertTrue(src.contains("togglePostStationAutoUnload"),
                "post station should expose an auto-unload toggle");
    }

    @Test
    void guiAndScreenWireAutoUnload() throws Exception {
        String pkt = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/network/packet/PostStationGuiActionPacket.java"));
        assertTrue(pkt.contains("TOGGLE_AUTO_UNLOAD"), "gui action packet should have TOGGLE_AUTO_UNLOAD");
        String screen = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/client/screen/PostStationScreen.java"));
        assertTrue(screen.contains("TOGGLE_AUTO_UNLOAD"), "post station screen should send TOGGLE_AUTO_UNLOAD");
        assertTrue(screen.contains("data.autoUnloadOnDispatch()"), "screen should read autoUnloadOnDispatch state");
    }

    @Test
    void langHasAutoUnload() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String key : new String[]{"screen.sailboatmod.post_station.auto_unload.on", "screen.sailboatmod.post_station.auto_unload.off"}) {
            assertTrue(zh.contains("\"" + key + "\""), "zh missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en missing " + key);
        }
    }
}
