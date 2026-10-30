package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.function.Predicate;

/** Never asks the world to supply a chunk. Unknown cells cannot validate a facility. */
public final class AirportValidation {
    public enum Result { VALID, WAITING, INVALID_GEOMETRY, TOO_LARGE, UNLOADED, OBSTRUCTED, UNSUPPORTED }
    private static final int MAX_CELLS = 262144;
    // Geometry limits: 512 runway + two 64 approaches + width 32 + raster rounding.
    private static final int MAX_HORIZONTAL_SPAN = 512 + 2 * 64 + 32 + 2;
    /** Conservative bound including both phases, all candidate columns and row setup. */
    public static final int MAX_SCAN_STEPS = 2 * (MAX_CELLS + MAX_HORIZONTAL_SPAN * MAX_HORIZONTAL_SPAN + MAX_HORIZONTAL_SPAN) + 1;
    private AirportValidation() {}
    /** Explicit editor/command validation; gameplay uses Scan with a bounded per-tick budget. */
    public static Result validate(AirportFacility facility, Predicate<BlockPos> loaded, Predicate<BlockPos> clear, Predicate<BlockPos> supported) {
        Scan scan = new Scan(facility);
        return scan.advance(scan.maximumSteps, loaded, clear, supported);
    }
    public static final class Scan {
        private final AirportFacility facility;
        private int minX, maxX, minZ, maxZ, x, y, z, rowMaxZ, airBase, airEnd, phase, used, actualCells, maximumSteps;
        private boolean rowReady, columnReady, support;
        private Result result = Result.WAITING;
        public Scan(AirportFacility facility) {
            this.facility = facility;
            if (facility == null || !facility.validGeometry()) { result = Result.INVALID_GEOMETRY; return; }
            AABB area = facility.clearanceBounds();
            minX = (int) Math.floor(area.minX); maxX = (int) Math.ceil(area.maxX) - 1;
            minZ = (int) Math.floor(area.minZ); maxZ = (int) Math.ceil(area.maxZ) - 1;
            // Bounds here only cap editor work. Capacity is counted from actual corridor cells in phase zero.
            maximumSteps = 2 * (MAX_CELLS + (maxX - minX + 1) * (maxZ - minZ + 1) + maxX - minX + 1) + 1;
            resetCursor();
        }
        public int stepsUsed() { return used; }
        public Result advance(int budget, Predicate<BlockPos> loaded, Predicate<BlockPos> clear, Predicate<BlockPos> supported) {
            used = 0;
            if (loaded == null || clear == null || supported == null) return result = Result.INVALID_GEOMETRY;
            while (result == Result.WAITING && used < Math.max(0, budget)) {
                used++;
                if (!rowReady) {
                    AirportFacility.ColumnRange range = facility.clearanceColumns(x);
                    z = Math.max(minZ, range.minZ()); rowMaxZ = Math.min(maxZ, range.maxZ());
                    rowReady = true;
                    if (z > rowMaxZ) nextRow();
                    continue;
                }
                if (!columnReady) {
                    airBase = facility.clearanceBaseY(x, z);
                    support = facility.requiresSupport(x, z);
                    int cells = (support ? 1 : 0) + (airBase == Integer.MIN_VALUE ? 0 : facility.clearanceHeight());
                    if (phase == 0 && (actualCells += cells) > MAX_CELLS) return result = Result.TOO_LARGE;
                    if (cells == 0) { nextColumn(); continue; }
                    airEnd = airBase == Integer.MIN_VALUE ? airBase : airBase + facility.clearanceHeight();
                    y = support ? facility.start().getY() - 1 : airBase;
                    columnReady = true;
                    continue;
                }
                BlockPos pos = new BlockPos(x, y, z);
                // Recheck in phase two: a chunk can unload while a long scan is pending.
                if (!loaded.test(pos)) return result = Result.UNLOADED;
                boolean supportCell = support && y == facility.start().getY() - 1;
                if (phase == 1) {
                    if (supportCell && !supported.test(pos)) return result = Result.UNSUPPORTED;
                    if (!supportCell && !clear.test(pos)) return result = Result.OBSTRUCTED;
                }
                if (supportCell && airBase != Integer.MIN_VALUE) y = airBase;
                else if (supportCell || ++y >= airEnd) nextColumn();
            }
            return result;
        }
        private void resetCursor() { x = minX; rowReady = false; columnReady = false; }
        private void nextColumn() {
            columnReady = false;
            if (++z > rowMaxZ) nextRow();
        }
        private void nextRow() {
            rowReady = false;
            if (++x <= maxX) return;
            if (++phase == 2) result = Result.VALID;
            else resetCursor();
        }
    }
}
