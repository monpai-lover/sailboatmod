package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Objects;

/** Player-built geometry. Endpoint Y is the free air cell immediately above the landing surface. */
public record AirportFacility(String id, Type type, BlockPos start, BlockPos end, int width, int clearanceHeight) {
    public enum Type { RUNWAY, HELIPAD, MOORING }
    public AirportFacility {
        Objects.requireNonNull(type);
        start = Objects.requireNonNull(start).immutable();
        end = Objects.requireNonNull(end).immutable();
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid facility id");
    }
    public double length() { return Math.hypot((double) end.getX() - start.getX(), (double) end.getZ() - start.getZ()); }
    public int approachLength() { return type == Type.RUNWAY ? Math.max(16, Math.min(64, (int) Math.ceil(length() / 2))) : 0; }
    public Vec3 landingPosition() { return new Vec3((start.getX() + (double) end.getX()) / 2 + .5, start.getY(), (start.getZ() + (double) end.getZ()) / 2 + .5); }
    public boolean validGeometry() {
        return coordinateInBounds(start) && coordinateInBounds(end)
                && start.getY() == end.getY() && width >= 5 && width <= 32 && clearanceHeight >= 4 && clearanceHeight <= 64
                && length() <= 512 && (type != Type.RUNWAY || length() >= 32)
                && (type == Type.RUNWAY || length() <= 32);
    }
    private static boolean coordinateInBounds(BlockPos pos) {
        return Math.abs((long) pos.getX()) <= 30_000_000 && Math.abs((long) pos.getZ()) <= 30_000_000
                && pos.getY() >= -2048 && pos.getY() <= 2047;
    }
    public boolean supports(AircraftClass airframe) {
        if (airframe == null || !validGeometry()) return false;
        return switch (airframe) {
            case FIXED_WING -> type == Type.RUNWAY;
            case ROTORCRAFT -> type == Type.RUNWAY || type == Type.HELIPAD;
            case AIRSHIP -> type == Type.MOORING;
        };
    }
    public boolean containsHorizontal(double x, double z, double inset) {
        double length = length();
        double dx = x - (start.getX() + .5), dz = z - (start.getZ() + .5);
        double ux = length < 1e-9 ? 1 : (end.getX() - (double) start.getX()) / length;
        double uz = length < 1e-9 ? 0 : (end.getZ() - (double) start.getZ()) / length;
        double along = dx * ux + dz * uz, across = -dx * uz + dz * ux;
        double half = width / 2.0 - inset;
        return half >= 0 && along >= -half - 1e-6 && along <= length + half + 1e-6 && Math.abs(across) <= half + 1e-6;
    }
    public AABB bounds() {
        return horizontalBounds(0, start.getY() + clearanceHeight);
    }
    public AABB clearanceBounds() {
        return horizontalBounds(approachLength(), start.getY() + clearanceHeight + Math.ceil(approachLength() / 4.0));
    }
    private AABB horizontalBounds(double approach, double maxY) {
        double half = width / 2.0;
        double length = length();
        double ux = length < 1e-9 ? 1 : (end.getX() - (double) start.getX()) / length;
        double uz = length < 1e-9 ? 0 : (end.getZ() - (double) start.getZ()) / length;
        double extensionX = half * (Math.abs(ux) + Math.abs(uz)) + approach * Math.abs(ux);
        double extensionZ = half * (Math.abs(ux) + Math.abs(uz)) + approach * Math.abs(uz);
        return new AABB(Math.min(start.getX(), end.getX()) + .5 - extensionX, start.getY(), Math.min(start.getZ(), end.getZ()) + .5 - extensionZ,
                Math.max(start.getX(), end.getX()) + .5 + extensionX, maxY, Math.max(start.getZ(), end.getZ()) + .5 + extensionZ);
    }
    /** Conservative row interval for the rotated corridor, avoiding its mostly empty outer rectangle. */
    ColumnRange clearanceColumns(int x) {
        double length = length(), half = width / 2.0, padding = Math.sqrt(.5);
        double ux = length < 1e-9 ? 1 : (end.getX() - (double) start.getX()) / length;
        double uz = length < 1e-9 ? 0 : (end.getZ() - (double) start.getZ()) / length;
        double dx = x - (double) start.getX();
        double[] range = {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY};
        if (!clipRange(range, uz, dx * ux, -half - approachLength() - padding, length + half + approachLength() + padding)
                || !clipRange(range, ux, -dx * uz, -half - padding, half + padding)) return new ColumnRange(1, 0);
        return new ColumnRange((int) Math.ceil(range[0] - 1e-7) + start.getZ(), (int) Math.floor(range[1] + 1e-7) + start.getZ());
    }
    private static boolean clipRange(double[] range, double coefficient, double offset, double low, double high) {
        if (Math.abs(coefficient) < 1e-12) return offset >= low - 1e-7 && offset <= high + 1e-7;
        double first = (low - offset) / coefficient, last = (high - offset) / coefficient;
        range[0] = Math.max(range[0], Math.min(first, last));
        range[1] = Math.min(range[1], Math.max(first, last));
        return range[0] <= range[1] + 1e-7;
    }
    record ColumnRange(int minZ, int maxZ) {}
    /** Conservative block-column intersection, including both sloped runway approaches. */
    int clearanceBaseY(int x, int z) {
        double padding = Math.sqrt(.5);
        if (intersectsDeckColumn(x, z)) return start.getY();
        if (type != Type.RUNWAY) return Integer.MIN_VALUE;
        double length = length(), ux = (end.getX() - (double) start.getX()) / length, uz = (end.getZ() - (double) start.getZ()) / length;
        double dx = x - (double) start.getX(), dz = z - (double) start.getZ();
        double along = dx * ux + dz * uz, across = -dx * uz + dz * ux;
        double half = width / 2.0;
        double beyond = Math.max(-half - along, along - (length + half));
        if (Math.abs(across) > half + padding || beyond > approachLength() + padding) return Integer.MIN_VALUE;
        return start.getY() + (int) Math.floor(Math.max(0, beyond - padding) / 4);
    }
    boolean requiresSupport(int x, int z) {
        return type == Type.MOORING ? x == start.getX() && z == start.getZ()
                : intersectsDeckColumn(x, z);
    }
    /** SAT for a unit world cell against the rotated deck, excluding boundary-only contact. */
    private boolean intersectsDeckColumn(int x, int z) {
        double length = length(), half = width / 2.0, alongHalf = length / 2 + half;
        double ux = length < 1e-9 ? 1 : (end.getX() - (double) start.getX()) / length;
        double uz = length < 1e-9 ? 0 : (end.getZ() - (double) start.getZ()) / length;
        double dx = x - (start.getX() + end.getX()) / 2.0, dz = z - (start.getZ() + end.getZ()) / 2.0;
        double cellProjection = .5 * (Math.abs(ux) + Math.abs(uz));
        return Math.abs(dx * ux + dz * uz) < alongHalf + cellProjection - 1e-7
                && Math.abs(-dx * uz + dz * ux) < half + cellProjection - 1e-7
                && Math.abs(dx) < alongHalf * Math.abs(ux) + half * Math.abs(uz) + .5 - 1e-7
                && Math.abs(dz) < alongHalf * Math.abs(uz) + half * Math.abs(ux) + .5 - 1e-7;
    }
    public boolean isParked(AABB hull, double speedMetersPerSecond) {
        if (!validGeometry() || hull == null || !Double.isFinite(speedMetersPerSecond) || speedMetersPerSecond < 0 || speedMetersPerSecond > .25) return false;
        if (!Double.isFinite(hull.minY) || !Double.isFinite(hull.maxY) || Math.abs(hull.minY - start.getY()) > .5 || hull.maxY > start.getY() + clearanceHeight) return false;
        return containsHorizontal(hull.minX, hull.minZ, 0) && containsHorizontal(hull.minX, hull.maxZ, 0)
                && containsHorizontal(hull.maxX, hull.minZ, 0) && containsHorizontal(hull.maxX, hull.maxZ, 0);
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id); tag.putString("Type", type.name());
        tag.putLong("Start", start.asLong()); tag.putLong("End", end.asLong());
        tag.putInt("Width", width); tag.putInt("ClearanceHeight", clearanceHeight);
        return tag;
    }
    public static AirportFacility load(CompoundTag tag) {
        AirportFacility value = new AirportFacility(tag.getString("Id"), Type.valueOf(tag.getString("Type")), BlockPos.of(tag.getLong("Start")), BlockPos.of(tag.getLong("End")), tag.getInt("Width"), tag.getInt("ClearanceHeight"));
        if (!value.validGeometry()) throw new IllegalArgumentException("Invalid facility geometry");
        return value;
    }
}
