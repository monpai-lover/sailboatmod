package com.monpai.sailboatmod;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Button captions AND the action labels used in single/batch confirmation messages must resolve. */
class ClaimSpawnLocalizationTest {
    private void check(String language) throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of(
                "src/main/resources/assets/sailboatmod/lang/" + language + ".json"))).getAsJsonObject();
        for (String key : new String[]{
                "screen.sailboatmod.nation.claims.button.mob_spawn",
                "screen.sailboatmod.nation.claims.action.mob_spawn",
                "screen.sailboatmod.nation.claims.mob_spawn_hint",
                "screen.sailboatmod.nation.access.true",
                "screen.sailboatmod.nation.access.false",
                "command.sailboatmod.nation.claimperm.action.mob_spawn",
                "command.sailboatmod.nation.claimperm.mob_spawn_success"}) {
            assertTrue(json.has(key), language + ": " + key);
            assertFalse(json.get(key).getAsString().isBlank(), language + ": " + key);
        }
        assertTrue(json.get("screen.sailboatmod.nation.claims.button.mob_spawn").getAsString().contains("%s"));
    }
    @Test void chineseSpawnPermissionLabelsResolve() throws Exception { check("zh_cn"); }
    @Test void englishSpawnPermissionLabelsResolve() throws Exception { check("en_us"); }
}
