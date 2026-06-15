package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangContractTest {
    private static final String[] KEYS = {
        "screen.sailboatmod.market.buy.modal.title",
        "screen.sailboatmod.market.buy.modal.mode.seller_ship",
        "screen.sailboatmod.market.buy.modal.mode.auto_pickup",
        "screen.sailboatmod.market.buy.modal.mode.real_pickup",
        "screen.sailboatmod.market.buy.modal.receiving.label",
        "screen.sailboatmod.market.buy.modal.receiving.none",
        "screen.sailboatmod.market.buy.modal.mode.need_warehouse",
        "screen.sailboatmod.market.buy.modal.mode.unreachable",
        "screen.sailboatmod.market.buy.modal.queue.position",
        "screen.sailboatmod.market.buy.modal.confirm",
        "screen.sailboatmod.market.buy.modal.cancel",
    };

    @Test
    void bothLangFilesHaveAllKeys() throws Exception {
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        for (String key : KEYS) {
            assertTrue(zh.contains("\"" + key + "\""), "zh_cn.json missing " + key);
            assertTrue(en.contains("\"" + key + "\""), "en_us.json missing " + key);
        }
    }
}
