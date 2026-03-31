package com.monpai.sailboatmod.resident.model;

import java.util.Locale;

/**
 * Town culture affects resident names, production bonuses, and flavor.
 */
public enum Culture {
    EUROPEAN("european", "European"),
    ASIAN("asian", "Asian"),
    NORDIC("nordic", "Nordic"),
    DESERT("desert", "Desert"),
    TROPICAL("tropical", "Tropical"),
    SLAVIC("slavic", "Slavic");

    private final String id;
    private final String displayName;

    Culture(String id, String displayName) { this.id = id; this.displayName = displayName; }
    public String id() { return id; }
    public String displayName() { return displayName; }

    public static Culture fromId(String id) {
        if (id == null || id.isBlank()) return EUROPEAN;
        for (Culture c : values()) if (c.id.equals(id.toLowerCase(Locale.ROOT))) return c;
        return EUROPEAN;
    }
}
