package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact;
import com.monpai.sailboatmod.transport.physical.NativeGroundObservation;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import java.util.UUID;

/** Validation of observations, separate from the native impulse sink. */
final class NativeTakeoffAssistEvidence {
    record Direction(Vector3d local, double horizontalSpeed, boolean valid) { }
    /** Native allocation can briefly be zero while a mechanically powered propeller is available. */
    static boolean powered(boolean active, double availableThrust) {
        return active && Double.isFinite(availableThrust) && availableThrust > 1e-6;
    }
    static Contact contact(NativeGroundObservation observed, UUID body, int expectedWheels) {
        if (observed == null || !observed.available() || body == null || !body.equals(observed.physicalId())
                || expectedWheels < 3 || expectedWheels > 32 || observed.wheels().size() != expectedWheels
                || !finite(observed.angularVelocity())) return Contact.UNKNOWN;
        int positive = 0;
        java.util.List<net.minecraft.world.phys.Vec3> supported = new java.util.ArrayList<>();
        for (var wheel : observed.wheels()) {
            if (wheel == null || !finite(wheel.worldCenter()) || !Double.isFinite(wheel.tireRadius())
                    || wheel.tireRadius() <= 0 || !Double.isFinite(wheel.suspensionCompression())
                    || wheel.suspensionCompression() < 0) return Contact.UNKNOWN;
            if (wheel.supported() && wheel.suspensionCompression() > 0) {
                positive++;
                supported.add(wheel.worldCenter());
            }
            else if (wheel.supported() || wheel.suspensionCompression() > 0) return Contact.UNKNOWN;
        }
        if (positive == expectedWheels) return observed.fullyGrounded() ? Contact.GROUNDED : Contact.UNKNOWN;
        if (positive == 0) return Contact.AIRBORNE;
        // Only complete support can arm the lifecycle. Once rolling, rotation can unload
        // three wheels before the final wheel leaves the surface. A fresh positive native
        // contact still proves ground support; the independent attitude/heading/slip checks
        // continue to reject a tipped or sideways airframe. Zero contacts never get thrust.
        if (positive == 1) return Contact.ROLLING;
        // Multiple purported contacts must remain genuinely separated.
        for (int i = 0; i < supported.size(); i++) for (int j = i + 1; j < supported.size(); j++) {
            var a = supported.get(i);
            var b = supported.get(j);
            double dx = a.x - b.x, dz = a.z - b.z;
            if (dx * dx + dz * dz > .25) return Contact.ROLLING;
        }
        return Contact.UNKNOWN;
    }
    /** Actual Offroad wheel brake inputs; native aerodynamic brake eligibility is not deployment. */
    static boolean wheelBrakesReleased(int[] signals) {
        if (signals == null || signals.length < 3 || signals.length > 32) return false;
        for (int signal : signals) if (signal != 0) return false;
        return true;
    }
    static boolean takeoffStage(String stage) {
        return "accelerating".equals(stage) || "takeoff_climb".equals(stage) || "takeoff_speed_recovery".equals(stage);
    }
    static Direction direction(Quaterniondc orientation, Vector3dc localForward, Vector3dc launchHeading, Vector3dc velocity) {
        Direction unknown = new Direction(new Vector3d(), 0, false);
        if (orientation == null || !finite(localForward) || !finite(launchHeading) || !finite(velocity)) return unknown;
        double norm = orientation.x()*orientation.x()+orientation.y()*orientation.y()
                +orientation.z()*orientation.z()+orientation.w()*orientation.w();
        if (!Double.isFinite(norm) || Math.abs(norm-1) > 1e-4 || localForward.lengthSquared() < .9) return unknown;
        Vector3d up = orientation.transform(new Vector3d(0, 1, 0));
        if (!finite(up) || up.y < Math.cos(Math.toRadians(25))) return unknown;
        Vector3d forward = orientation.transform(new Vector3d(localForward)).normalize();
        if (!finite(forward) || Math.abs(forward.y) > .35) return unknown;
        forward.y = 0;
        if (forward.lengthSquared() < 1e-6) return unknown;
        forward.normalize();
        Vector3d launch = new Vector3d(launchHeading.x(), 0, launchHeading.z());
        if (launch.lengthSquared() < 1e-6 || forward.dot(launch.normalize()) < Math.cos(Math.toRadians(15))) return unknown;
        double speed = Math.hypot(velocity.x(), velocity.z());
        double along = forward.x*velocity.x()+forward.z*velocity.z();
        if (!Double.isFinite(speed) || along < -.1
                || (speed > 2 && along/speed < Math.cos(Math.toRadians(20)))) return unknown;
        Vector3d local = orientation.transformInverse(new Vector3d(forward));
        return new Direction(local, speed, finite(local));
    }
    private static boolean finite(Vector3dc v) { return v != null && Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z()); }
    private static boolean finite(net.minecraft.world.phys.Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
