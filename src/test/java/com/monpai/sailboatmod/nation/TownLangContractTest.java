package com.monpai.sailboatmod.nation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TownLangContractTest {
    private static final String[] KEYS = {
            "command.sailboatmod.town.not_found",
            "command.sailboatmod.town.already_member",
            "command.sailboatmod.town.apply.success",
            "command.sailboatmod.town.apply.already_sent",
            "command.sailboatmod.town.apply.missing",
            "command.sailboatmod.town.apply.reject.success",
            "command.sailboatmod.town.invite.success",
            "command.sailboatmod.town.invite.already_sent",
            "command.sailboatmod.town.invite.not_mayor",
            "command.sailboatmod.town.join.success",
            "command.sailboatmod.town.decline.success",
            "command.sailboatmod.town.decline.missing",
            "command.sailboatmod.town.leave.success",
            "command.sailboatmod.town.leave.not_member",
            "command.sailboatmod.town.kick.success",
            "command.sailboatmod.town.kick.not_member",
            "command.sailboatmod.town.kick.is_mayor",
            "command.sailboatmod.town.list.empty",
            "command.sailboatmod.town.list.header",
            "command.sailboatmod.town.list.entry",
            "command.sailboatmod.town.members.empty",
            "command.sailboatmod.town.members.header",
            "command.sailboatmod.town.members.entry",
            "command.sailboatmod.town.requests.empty",
            "command.sailboatmod.town.requests.header",
            "command.sailboatmod.town.requests.entry",
    };

    @Test
    void bothLangFilesContainAllTownKeys() throws Exception {
        String en = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        String zh = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));
        for (String key : KEYS) {
            assertTrue(en.contains("\"" + key + "\""), "en_us.json missing " + key);
            assertTrue(zh.contains("\"" + key + "\""), "zh_cn.json missing " + key);
        }
    }
}
