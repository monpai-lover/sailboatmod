package com.monpai.sailboatmod.route.water;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TellusHintLangTest {
    private static final String KEY = "screen.sailboatmod.ui.route_mode.tellus_hint";

    @Test
    void englishHintExplainsAutomaticTellusPlanning() throws IOException {
        assertEquals("In a Tellus Earth world the server always plans with real terrain plus existing blocks.",
                lang("en_us").get(KEY).getAsString());
    }

    @Test
    void simplifiedChineseHintMatches() throws IOException {
        assertEquals("在 Tellus 地球世界中，服务器会自动使用真实地形＋既有方块规划。", lang("zh_cn").get(KEY).getAsString());
    }

    private static JsonObject lang(String code) throws IOException {
        String text = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/" + code + ".json"), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
