package com.monpai.sailboatmod.route.water.debug;

import com.monpai.sailboatmod.route.water.WaterMidMode;

/** HTTP-only choices. These values are never encoded in a game packet. */
public enum RouteDebugMode {
    TELLUS(WaterMidMode.NBT),
    NBT(WaterMidMode.NBT),
    HYBRID(WaterMidMode.HYBRID),
    NOISE(WaterMidMode.NOISE);

    private final WaterMidMode plannerMode;

    RouteDebugMode(WaterMidMode plannerMode) { this.plannerMode = plannerMode; }

    public WaterMidMode plannerMode() { return plannerMode; }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    public static RouteDebugMode defaultFor(boolean tellusAvailable) { return tellusAvailable ? TELLUS : NBT; }

    public static RouteDebugMode parse(String value) {
        if (value == null) return null;
        if ("tellus".equalsIgnoreCase(value.trim())) return TELLUS;
        WaterMidMode legacy = WaterMidMode.parse(value);
        if (legacy == null) return null;
        return switch (legacy) {
            case NBT -> NBT;
            case HYBRID -> HYBRID;
            case NOISE -> NOISE;
        };
    }
}
