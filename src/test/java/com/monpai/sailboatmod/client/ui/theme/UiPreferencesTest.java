package com.monpai.sailboatmod.client.ui.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiPreferencesTest {
    @TempDir
    Path dir;

    @Test
    void themeIsSavedAndReadBack() {
        Path file = dir.resolve(UiPreferences.FILE_NAME);
        UiPreferences prefs = new UiPreferences(file);
        assertEquals(UiTheme.TRADING_HALL, prefs.theme());
        assertEquals(UiTheme.LEDGER, prefs.toggleTheme());
        assertEquals(UiTheme.LEDGER, new UiPreferences(file).theme());
    }

    @Test
    void openScreensAreToldAboutChangesOnlyWhenTheThemeChanges() {
        UiPreferences prefs = new UiPreferences(dir.resolve(UiPreferences.FILE_NAME));
        List<UiTheme> seen = new ArrayList<>();
        prefs.addListener(seen::add);
        prefs.setTheme(UiTheme.TRADING_HALL);
        prefs.setTheme(UiTheme.LEDGER);
        assertEquals(List.of(UiTheme.LEDGER), seen);
    }

    @Test
    void brokenFileFallsBackToDefault() throws IOException {
        Path file = dir.resolve(UiPreferences.FILE_NAME);
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        assertEquals(UiTheme.TRADING_HALL, new UiPreferences(file).theme());
        Files.writeString(file, "{\"theme\":\"neon\"}", StandardCharsets.UTF_8);
        assertEquals(UiTheme.TRADING_HALL, new UiPreferences(file).theme());
    }

    @Test
    void bothThemesShipAStylesheetCoveringTheSameSelectors() throws IOException {
        Path lss = Path.of("src/main/resources/assets/sailboatmod/lss");
        List<String> hall = selectors(Files.readString(lss.resolve("trading_hall.lss")));
        List<String> ledger = selectors(Files.readString(lss.resolve("ledger.lss")));
        assertTrue(hall.size() > 20);
        assertEquals(hall, ledger, "switching themes must not leave any element unstyled");
        for (UiTheme theme : UiTheme.values()) {
            assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/sailboatmod").resolve(theme.stylesheetPath())));
            for (String texture : List.of("slot.png", "slot_hover.png", "slot_selected.png", "button_primary.png",
                    "button_secondary.png", "button_danger.png", "button_disabled.png", "tab_active.png")) {
                assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/sailboatmod/textures/gui", theme.id(), texture)),
                        theme.id() + " is missing " + texture);
            }
        }
    }

    private static List<String> selectors(String lss) {
        return lss.lines()
                .map(String::trim)
                .filter(line -> line.endsWith("{") || line.contains("{ "))
                .map(line -> line.substring(0, line.indexOf('{')).trim())
                .toList();
    }
}
