package com.monpai.sailboatmod.block.entity;

/** Mirrors the registrar's package-private configuration and calibration records. */
public final class HiddenTakeoffCalibrationFixture {
    private HiddenTakeoffCalibrationFixture() { }
    public static Object configuration() { return new Configuration(new Calibration(true)); }
    record Configuration(Calibration calibration) { }
    record Calibration(boolean liveVerified) { }
}
