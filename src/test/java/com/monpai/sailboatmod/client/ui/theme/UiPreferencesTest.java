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
    void marketWatchlistIsSavedWithTheThemeAndSurvivesBrokenEntries() throws IOException {
        Path file = dir.resolve(UiPreferences.FILE_NAME);
        UiPreferences prefs = new UiPreferences(file);
        assertTrue(prefs.toggleMarketWatch("minecraft:diamond"));
        assertTrue(prefs.toggleMarketWatch("minecraft:coal"));
        prefs.toggleTheme();
        UiPreferences reloaded = new UiPreferences(file);
        assertEquals(List.of("minecraft:diamond", "minecraft:coal"), List.copyOf(reloaded.marketWatchlist()));
        assertEquals(UiTheme.LEDGER, reloaded.theme(), "saving the watchlist keeps the theme and the other way round");
        assertEquals(false, reloaded.toggleMarketWatch("minecraft:diamond"));
        assertEquals(List.of("minecraft:coal"), List.copyOf(new UiPreferences(file).marketWatchlist()));
        assertEquals(false, reloaded.toggleMarketWatch(" "));

        Files.writeString(file, "{\"theme\":\"ledger\",\"marketWatchlist\":[\"minecraft:wheat\",3,\"\",{\"x\":1}]}", StandardCharsets.UTF_8);
        assertEquals(List.of("minecraft:wheat", "3"), List.copyOf(new UiPreferences(file).marketWatchlist()));
        Files.writeString(file, "{\"marketWatchlist\":\"minecraft:wheat\"}", StandardCharsets.UTF_8);
        assertEquals(List.of(), List.copyOf(new UiPreferences(file).marketWatchlist()));
    }

    @Test
    void marketWatchlistIsBounded() {
        UiPreferences prefs = new UiPreferences(dir.resolve(UiPreferences.FILE_NAME));
        for (int i = 0; i < UiPreferences.MAX_WATCHED + 10; i++) {
            prefs.toggleMarketWatch("mod:item_" + i);
        }
        assertEquals(UiPreferences.MAX_WATCHED, prefs.marketWatchlist().size());
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
