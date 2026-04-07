package com.monpai.sailboatmod.market;

import java.util.Locale;

public enum TransportTerminalKind {
    AUTO,
    PORT,
    POST_STATION;

    public static TransportTerminalKind fromName(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return TransportTerminalKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AUTO;
        }
    }
}
