package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.transport.board.TownVoyageSnapshot;
import java.util.OptionalLong;

public final class RailFreightEta {
    private RailFreightEta() {}

    /** Predict the active leg from the measured speed, accelerating to cruise and braking at the station. */
    public static OptionalLong activeLegTicks(double distance, double speed, double cruise, double acceleration) {
        if (!Double.isFinite(distance) || distance < 0 || !Double.isFinite(speed) || speed < 0
                || !Double.isFinite(cruise) || cruise <= 0 || !Double.isFinite(acceleration) || acceleration <= 0) {
            return OptionalLong.empty();
        }

        double initial = Math.min(speed, cruise);
        double brakingDistance = initial * initial / (2 * acceleration);
        double duration;
        if (distance < brakingDistance) {
            // Native ticks can bring the train inside its ideal braking distance.
            double arrivalSpeed = Math.sqrt(Math.max(0, initial * initial - 2 * acceleration * distance));
            duration = 2 * distance / (initial + arrivalSpeed);
        } else {
            double peak = Math.min(cruise, Math.sqrt(acceleration * distance + initial * initial / 2));
            double rampDistance = peak * peak / acceleration - brakingDistance;
            double cruiseDistance = Math.max(0, distance - rampDistance);
            duration = (2 * peak - initial) / acceleration;
            if (cruiseDistance > 0) duration += cruiseDistance / peak;
        }

        return Double.isFinite(duration) && duration >= 0 && duration <= TownVoyageSnapshot.MAX_ETA_TICKS
                ? OptionalLong.of(Math.max(1, (long) Math.ceil(duration))) : OptionalLong.empty();
    }
}
