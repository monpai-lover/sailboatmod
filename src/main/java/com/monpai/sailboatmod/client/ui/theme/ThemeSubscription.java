package com.monpai.sailboatmod.client.ui.theme;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps a retained UI subscribed to theme preferences only while it is mounted. */
public final class ThemeSubscription {
    private final Supplier<UiPreferences> preferences;
    private final Consumer<UiTheme> listener;
    private UiPreferences mountedPreferences;

    public ThemeSubscription(Supplier<UiPreferences> preferences, Consumer<UiTheme> listener) {
        this.preferences = preferences;
        this.listener = listener;
    }

    /** Applies changes made while hidden, then listens for changes until the next unmount. */
    public void mount() {
        if (mountedPreferences != null) {
            return;
        }
        UiPreferences current = preferences.get();
        listener.accept(current.theme());
        current.addListener(listener);
        mountedPreferences = current;
    }

    public void unmount() {
        if (mountedPreferences != null) {
            mountedPreferences.removeListener(listener);
            mountedPreferences = null;
        }
    }
}
