package com.monpai.sailboatmod.transport.air;

/** Native follower flags can change to holding or landing during one update. */
public record PitchBootstrapFlightPhase(boolean cruising, boolean takingOff, boolean groundRoll,
                                        boolean landing, boolean following, boolean holding, boolean orbiting) {
    public boolean allowsMeasuredPitch() {
        return cruising && !takingOff && !groundRoll && !landing && !following && !holding && !orbiting;
    }
}
