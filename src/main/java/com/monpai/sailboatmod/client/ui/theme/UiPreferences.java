package com.monpai.sailboatmod.client.ui.theme;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client-only UI preferences stored in {@code config/sailboatmod-ui.json}.
 *
 * <p>Kept separate from the shared {@code ModConfig} on purpose: the theme is a per-player display choice with no
 * server meaning. A missing or broken file reads as defaults and is never fatal.</p>
 */
public final class UiPreferences {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String FILE_NAME = "sailboatmod-ui.json";

    private static UiPreferences instance;

    private final Path file;
    private final List<Consumer<UiTheme>> listeners = new CopyOnWriteArrayList<>();
    private UiTheme theme = UiTheme.TRADING_HALL;

    UiPreferences(Path file) {
        this.file = file;
        load();
    }

    /** The shared instance, backed by the game's config directory. */
    public static synchronized UiPreferences get() {
        if (instance == null) {
            instance = new UiPreferences(net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(FILE_NAME));
        }
        return instance;
    }

    public UiTheme theme() {
        return theme;
    }

    /** Changes the theme, saves it and tells every open screen to restyle. */
    public void setTheme(UiTheme value) {
        UiTheme next = value == null ? UiTheme.TRADING_HALL : value;
        if (next == theme) {
            return;
        }
        theme = next;
        save();
        for (Consumer<UiTheme> listener : listeners) {
            listener.accept(next);
        }
    }

    public UiTheme toggleTheme() {
        setTheme(theme.next());
        return theme;
    }

    /** Screens register while open and must call {@link #removeListener} when they close. */
    public void addListener(Consumer<UiTheme> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<UiTheme> listener) {
        listeners.remove(listener);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonObject json = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (json != null && json.has("theme")) {
                theme = UiTheme.fromId(json.get("theme").getAsString());
            }
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            LOGGER.warn("Ignoring unreadable UI preferences at {}: {}", file, e.getMessage());
        }
    }

    private void save() {
        JsonObject json = new JsonObject();
        json.addProperty("theme", theme.id());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Could not save UI preferences to {}: {}", file, e.getMessage());
        }
    }
}
