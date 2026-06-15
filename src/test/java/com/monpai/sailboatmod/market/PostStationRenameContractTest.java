package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostStationRenameContractTest {
    @Test
    void renamePacketRegisteredAndCallsSetName() throws Exception {
        String net = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/network/ModNetwork.java"));
        assertTrue(net.contains("RenamePostStationPacket"),
                "RenamePostStationPacket should be registered in ModNetwork");
        String pkt = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/network/packet/RenamePostStationPacket.java"));
        assertTrue(pkt.contains("station.setDockName(packet.stationName)"),
                "rename handler should set the station name");
        assertTrue(pkt.contains("OpenPostStationScreenPacket"),
                "rename handler should refresh the post station screen");
    }

    @Test
    void screenHasRenameInput() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/client/screen/PostStationScreen.java"));
        assertTrue(screen.contains("new RenamePostStationPacket(data.stationPos(), nameInput.getValue())"),
                "post station screen should send rename packet with the input value");
    }

    @Test
    void langHasRenameKeys() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String key : new String[]{"screen.sailboatmod.post_station.rename.hint", "screen.sailboatmod.post_station.rename.save"}) {
            assertTrue(zh.contains("\"" + key + "\""), "zh missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en missing " + key);
        }
    }
}
