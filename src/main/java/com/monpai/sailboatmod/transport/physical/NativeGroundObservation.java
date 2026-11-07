package com.monpai.sailboatmod.transport.physical;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.UUID;

/** Fresh native wheel casts and angular velocity. Unknown evidence never counts as ground support. */
public record NativeGroundObservation(UUID physicalId, Vec3 angularVelocity, List<WheelContact> wheels,
                                      boolean available, String reason) {
    public NativeGroundObservation { wheels = List.copyOf(wheels); }
    public record WheelContact(Vec3 worldCenter, double tireRadius, double suspensionCompression, boolean supported) { }
    public boolean fullyGrounded() {
        if (!available || physicalId == null || !finite(angularVelocity) || wheels.size() > 32) return false;
        List<WheelContact> contacts = wheels.stream().filter(w -> w != null && w.supported() && finite(w.worldCenter())
                && Double.isFinite(w.tireRadius()) && w.tireRadius() > 0 && Double.isFinite(w.suspensionCompression())
                && w.suspensionCompression() > 0).toList();
        if (contacts.size() < 3) return false;
        // A single axle or duplicate mounts cannot establish stable whole-aircraft support.
        for (int i=0;i<contacts.size()-2;i++) for (int j=i+1;j<contacts.size()-1;j++) for (int k=j+1;k<contacts.size();k++) {
            Vec3 a=contacts.get(i).worldCenter(), b=contacts.get(j).worldCenter(), c=contacts.get(k).worldCenter();
            if (Math.abs((b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x)) > .5) return true;
        }
        return false;
    }
    private static boolean finite(Vec3 v) { return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z); }
    public static NativeGroundObservation unavailable(String reason) {
        return new NativeGroundObservation(null, Vec3.ZERO, List.of(), false, reason);
    }
}
