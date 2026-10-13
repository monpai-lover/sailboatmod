package com.monpai.sailboatmod.client.ui.theme;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeSubscriptionTest {
    @TempDir
    Path dir;

    @Test
    void preferencesAreResolvedOnlyWhenTheScreenIsMounted() {
        UiPreferences preferences = preferences();
        AtomicInteger lookups = new AtomicInteger();
        ThemeSubscription subscription = new ThemeSubscription(() -> {
            lookups.incrementAndGet();
            return preferences;
        }, theme -> { });

        assertEquals(0, lookups.get(), "constructing a screen must not require the live client config path");
        subscription.mount();
        subscription.mount();

        assertEquals(1, lookups.get());
    }

    @Test
    void mountingAppliesTheCurrentThemeImmediately() {
        UiPreferences preferences = preferences();
        preferences.setTheme(UiTheme.LEDGER);
        List<UiTheme> seen = new ArrayList<>();
        ThemeSubscription subscription = new ThemeSubscription(() -> preferences, seen::add);

        subscription.mount();

        assertEquals(List.of(UiTheme.LEDGER), seen);
    }

    @Test
    void repeatedMountsSubscribeOnlyOnce() {
        UiPreferences preferences = preferences();
        List<UiTheme> seen = new ArrayList<>();
        ThemeSubscription subscription = new ThemeSubscription(() -> preferences, seen::add);

        subscription.mount();
        subscription.mount();
        preferences.setTheme(UiTheme.LEDGER);

        assertEquals(List.of(UiTheme.TRADING_HALL, UiTheme.LEDGER), seen,
                "resizing a mounted screen must not duplicate its listener or restyle callback");
    }

    @Test
    void unmountedScreenReceivesNoThemeChanges() {
        UiPreferences preferences = preferences();
        List<UiTheme> seen = new ArrayList<>();
        ThemeSubscription subscription = new ThemeSubscription(() -> preferences, seen::add);
        subscription.mount();
        assertEquals(List.of(UiTheme.TRADING_HALL), seen);

        subscription.unmount();
        subscription.unmount();
        preferences.setTheme(UiTheme.LEDGER);

        assertEquals(List.of(UiTheme.TRADING_HALL), seen,
                "a retained parent screen must not restyle while its child is open");
    }

    @Test
    void returningToTheSameScreenUsesTheLatestThemeAndResubscribes() {
        UiPreferences preferences = preferences();
        List<UiTheme> seen = new ArrayList<>();
        ThemeSubscription subscription = new ThemeSubscription(() -> preferences, seen::add);
        subscription.mount();
        assertEquals(List.of(UiTheme.TRADING_HALL), seen);
        subscription.unmount();
        preferences.setTheme(UiTheme.LEDGER);

        subscription.mount();
        subscription.mount();
        preferences.setTheme(UiTheme.TRADING_HALL);

        assertEquals(List.of(UiTheme.TRADING_HALL, UiTheme.LEDGER, UiTheme.TRADING_HALL), seen,
                "returning must apply changes made in the child and receive later changes once");
    }

    private UiPreferences preferences() {
        return new UiPreferences(dir.resolve(UiPreferences.FILE_NAME));
    }
}
