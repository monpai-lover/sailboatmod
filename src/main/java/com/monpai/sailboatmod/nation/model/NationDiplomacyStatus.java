package com.monpai.sailboatmod.nation.model;

import java.util.Locale;

public enum NationDiplomacyStatus {
    ALLIED("allied"),
    TRADE("trade"),
    ENEMY("enemy"),
    NEUTRAL("neutral");

    private final String id;

    NationDiplomacyStatus(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static NationDiplomacyStatus fromId(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return NEUTRAL;
        }
        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        for (NationDiplomacyStatus value : values()) {
            if (value.id.equals(normalized)) {
                return value;
            }
        }
        return NEUTRAL;
    }
}