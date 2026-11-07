package com.monpai.sailboatmod.transport.air.airport;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A separate cargo stop. Y is the air plane over its supporting surface; yaw uses Minecraft degrees. */
public record AirportParkingStand(String id, Vec3 position, double yawDegrees, double length, double width,
                                  int clearanceHeight, boolean cargoEnabled) {
    public AirportParkingStand {
        if (!AirportGroundLayout.validId(id)) throw new IllegalArgumentException("Invalid stand id");
        Objects.requireNonNull(position);
    }
    public boolean validGeometry() {
        return AirportGroundLayout.validPoint(position) && Double.isFinite(yawDegrees)
                && Math.abs(yawDegrees) <= 360 && Double.isFinite(length) && length >= 2 && length <= 64
                && Double.isFinite(width) && width >= 2 && width <= 64 && clearanceHeight >= 4 && clearanceHeight <= 64;
    }
    public boolean contains(AABB hull, double actualYawDegrees, double speedMetersPerSecond) {
        if (!validGeometry() || !AirportGroundLayout.finiteHull(hull) || !Double.isFinite(actualYawDegrees)
                || !Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0 || speedMetersPerSecond > .25
                || AirportGroundLayout.angleDifference(actualYawDegrees, yawDegrees) > 10
                || hull.minY < position.y - .5 || hull.maxY > position.y + clearanceHeight) return false;
        return containsHorizontal(hull.minX,hull.minZ) && containsHorizontal(hull.minX,hull.maxZ)
                && containsHorizontal(hull.maxX,hull.minZ) && containsHorizontal(hull.maxX,hull.maxZ);
    }
    public boolean containsHorizontal(double x, double z) {
        double radians=Math.toRadians(yawDegrees), forwardX=-Math.sin(radians), forwardZ=Math.cos(radians);
        double dx=x-position.x,dz=z-position.z;
        return Math.abs(dx*forwardX+dz*forwardZ) <= length/2+1e-7
                && Math.abs(dx*forwardZ-dz*forwardX) <= width/2+1e-7;
    }
    public AABB bounds() {
        double radians=Math.toRadians(yawDegrees);
        double halfX=(Math.abs(Math.sin(radians))*length+Math.abs(Math.cos(radians))*width)/2;
        double halfZ=(Math.abs(Math.cos(radians))*length+Math.abs(Math.sin(radians))*width)/2;
        return new AABB(position.x-halfX,position.y,position.z-halfZ,position.x+halfX,position.y+clearanceHeight,position.z+halfZ);
    }
    public CompoundTag save() {
        CompoundTag tag=new CompoundTag(); tag.putString("Id",id); AirportGroundLayout.putPoint(tag,position);
        tag.putDouble("Yaw",yawDegrees);tag.putDouble("Length",length);tag.putDouble("Width",width);
        tag.putInt("ClearanceHeight",clearanceHeight);tag.putBoolean("CargoEnabled",cargoEnabled);return tag;
    }
    public static AirportParkingStand load(CompoundTag tag) {
        AirportParkingStand value=new AirportParkingStand(tag.getString("Id"),AirportGroundLayout.getPoint(tag),tag.getDouble("Yaw"),
                tag.getDouble("Length"),tag.getDouble("Width"),tag.getInt("ClearanceHeight"),tag.getBoolean("CargoEnabled"));
        if (!value.validGeometry()) throw new IllegalArgumentException("Invalid stand geometry"); return value;
    }
}
