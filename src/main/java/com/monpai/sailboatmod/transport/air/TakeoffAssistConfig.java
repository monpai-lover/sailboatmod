package com.monpai.sailboatmod.transport.air;

/** User-configurable additional physical takeoff acceleration, in m/s squared. */
public record TakeoffAssistConfig(boolean enabled, double acceleration) {
    public static final TakeoffAssistConfig DEFAULT = new TakeoffAssistConfig(false, 12);

    public TakeoffAssistConfig {
        acceleration = Double.isFinite(acceleration) ? Math.clamp(acceleration, 0, 20) : 0;
    }

    public TakeoffAssistConfig withEnabled(boolean value) {
        return new TakeoffAssistConfig(value, acceleration);
    }

    public TakeoffAssistConfig withAcceleration(double value) {
        return new TakeoffAssistConfig(enabled, value);
    }
}
