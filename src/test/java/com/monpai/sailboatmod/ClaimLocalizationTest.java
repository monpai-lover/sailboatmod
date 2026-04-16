package com.monpai.sailboatmod;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClaimLocalizationTest {
    private static final Gson GSON = new Gson();
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() { }.getType();
    private static final Path EN_US_PATH = Path.of("src", "main", "resources", "assets", "sailboatmod", "lang", "en_us.json");
    private static final Path ZH_CN_PATH = Path.of("src", "main", "resources", "assets", "sailboatmod", "lang", "zh_cn.json");

    @Test
    void zhCnLocalizationDoesNotContainKnownMojibake() throws IOException {
        Map<String, String> zhCn = loadLocalization(ZH_CN_PATH);

        assertEquals("\u6240\u6709\u4eba", zhCn.get("screen.sailboatmod.nation.access.anyone"));
        assertEquals("\u6240\u6709\u4eba", zhCn.get("command.sailboatmod.nation.claimperm.level.anyone"));
        assertEquals("\u5df2\u9009\u533a\u5757\uff1a%s, %s", zhCn.get("screen.sailboatmod.nation.claims.selected_chunk"));
        assertEquals("\u5df2\u9009\u533a\u5757\uff1a%s, %s", zhCn.get("screen.sailboatmod.town.claims.selected_chunk"));
        assertEquals("\u8239\u4e3b\uff1a%s (%s)", zhCn.get("screen.sailboatmod.owner_full"));
        assertEquals("\u5df2\u9009\u62e9 %s", zhCn.get("screen.sailboatmod.nation.upload.selected"));
        assertEquals("\u5df2\u9009\u62e9: %s", zhCn.get("item.sailboatmod.structure.selected"));
        assertEquals("\u72b6\u6001\uff1a%s", zhCn.get("command.sailboatmod.nation.war.info.status"));
        assertEquals("\u72b6\u6001\uff1a%s", zhCn.get("screen.sailboatmod.nation.diplomacy.status"));
        assertEquals("\u72b6\u6001\uff1a\u79bb\u7ebf", zhCn.get("screen.sailboatmod.nation.members.status.offline"));
        assertEquals("\u72b6\u6001\uff1a\u79bb\u7ebf", zhCn.get("screen.sailboatmod.town.members.status.offline"));
        assertEquals("\u5269\u4f59\u6750\u6599", zhCn.get("overlay.sailboatmod.constructor.materials"));
        assertEquals("%s \u5efa\u7b51\u5df2\u5b8c\u6210\uff01", zhCn.get("command.sailboatmod.nation.structure.placed"));
        assertEquals("%s \u5efa\u7b51\u5df2\u5f00\u59cb\uff01", zhCn.get("command.sailboatmod.nation.structure.started"));
        assertFalse(zhCn.values().stream().anyMatch(value -> value != null && value.indexOf('\u20AC') >= 0));
    }

    @Test
    void enUsLocalizationContainsEveryZhCnKey() throws IOException {
        Map<String, String> enUs = loadLocalization(EN_US_PATH);
        Map<String, String> zhCn = loadLocalization(ZH_CN_PATH);

        Set<String> missingKeys = new TreeSet<>(zhCn.keySet());
        missingKeys.removeAll(enUs.keySet());

        assertEquals(Set.of(), missingKeys, "Missing en_us localization keys: " + missingKeys);
    }

    private static Map<String, String> loadLocalization(Path path) throws IOException {
        return GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), STRING_MAP);
    }
}
