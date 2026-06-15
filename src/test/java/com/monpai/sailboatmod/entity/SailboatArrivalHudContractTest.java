package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帆船到站 HUD 应与马车一致：同步到站提示倒计时/站名/耗时/日期，到达目的地时触发反馈，
 * 并由渲染器以全息文本绘制。
 */
class SailboatArrivalHudContractTest {
    @Test
    void sailboatHasArrivalNoticeEntityData() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        assertTrue(src.contains("DATA_ARRIVAL_NOTICE_UNTIL_TICK"),
                "sailboat should have an arrival-notice countdown accessor");
        assertTrue(src.contains("DATA_ARRIVAL_NOTICE_STATION_NAME"),
                "sailboat should sync the arrival station name");
        assertTrue(src.contains("DATA_ARRIVAL_NOTICE_ELAPSED_SECONDS"),
                "sailboat should sync the trip elapsed seconds");
        assertTrue(src.contains("DATA_ARRIVAL_NOTICE_DATE_TEXT"),
                "sailboat should sync the arrival date text");
    }

    @Test
    void sailboatExposesArrivalNoticeGetters() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        assertTrue(src.contains("public int getArrivalNoticeTicks()"),
                "renderer needs a remaining-ticks getter");
        assertTrue(src.contains("public String getArrivalNoticeStationName()"),
                "renderer needs the station-name getter");
        assertTrue(src.contains("public String getArrivalNoticeElapsedText()"),
                "renderer needs the elapsed-text getter");
        assertTrue(src.contains("public String getArrivalNoticeDateText()"),
                "renderer needs the date-text getter");
    }

    @Test
    void sailboatTriggersArrivalFeedbackOnDestination() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        assertTrue(src.contains("beginArrivalFeedback("),
                "sailboat should begin arrival feedback when it reaches its destination dock");
    }

    @Test
    void rendererDrawsArrivalHologram() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/client/renderer/SailboatEntityRenderer.java"));
        assertTrue(src.contains("getArrivalNoticeTicks()"),
                "sailboat renderer should gate the hologram on arrival-notice ticks");
        assertTrue(src.contains("drawInBatch"),
                "sailboat renderer should draw the arrival hologram text");
    }

    @Test
    void langHasSailboatArrivalKeys() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String key : new String[]{
                "entity.sailboatmod.sailboat.arrived",
                "entity.sailboatmod.sailboat.arrival.station",
                "entity.sailboatmod.sailboat.arrival.elapsed",
                "entity.sailboatmod.sailboat.arrival.date"}) {
            assertTrue(zh.contains("\"" + key + "\""), "zh missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en missing " + key);
        }
    }
}
