package com.monpai.sailboatmod.transport.air;

/** Conservative stopping admission only; it does not certify takeoff acceleration. */
public final class FixedWingRunwayPolicy {
    private FixedWingRunwayPolicy() { }
    public static String failure(double departureLength, double arrivalLength, double speed,
                                 double brakingDeceleration, double diameter) {
        if (!Double.isFinite(speed) || speed <= 0) return "fixed_wing_speed_invalid";
        if (!Double.isFinite(brakingDeceleration) || brakingDeceleration <= 0)
            return "ground_calibration_required";
        if (!stoppingFits(departureLength, speed, brakingDeceleration, diameter)) return "departure_runway_too_short";
        if (!stoppingFits(arrivalLength, speed, brakingDeceleration, diameter)) return "arrival_runway_too_short";
        return "";
    }
    public static boolean stoppingFits(double length, double speed, double deceleration, double diameter) {
        return Double.isFinite(length) && length > 0 && Double.isFinite(speed) && speed > 0
                && Double.isFinite(deceleration) && deceleration > 0 && Double.isFinite(diameter) && diameter > 0
                && speed * .25 + speed * speed / (2 * deceleration) + diameter / 2 + 2 <= length;
    }
}
