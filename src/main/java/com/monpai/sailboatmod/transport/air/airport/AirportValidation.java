package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/** Never asks the world to supply a chunk. Unknown cells cannot validate a facility. */
public final class AirportValidation {
    public enum Result { VALID, WAITING, INVALID_GEOMETRY, TOO_LARGE, UNLOADED, OBSTRUCTED, UNSUPPORTED }
    // Includes legal runway/approach raster padding, support and maximum clearance height.
    private static final int MAX_HORIZONTAL_SPAN = AirportFacility.MAX_RUNWAY_CLEARANCE_SPAN;
    public static final int MAX_CELLS = MAX_HORIZONTAL_SPAN * (AirportFacility.MAX_WIDTH + 2)
            * (AirportFacility.MAX_CLEARANCE_HEIGHT + 1);
    /** Conservative bound including both phases, all candidate columns and row setup. */
    public static final int MAX_SCAN_STEPS = 2 * (MAX_CELLS + MAX_HORIZONTAL_SPAN * MAX_HORIZONTAL_SPAN + MAX_HORIZONTAL_SPAN) + 1;
    private AirportValidation() {}
    /** Pure/offline convenience. Every server editor, command and gameplay path must use bounded Scan steps. */
    public static Result validate(AirportFacility facility, Predicate<BlockPos> loaded, Predicate<BlockPos> clear, Predicate<BlockPos> supported) {
        Scan scan = new Scan(facility);
        return scan.advance(scan.maximumSteps, loaded, clear, supported);
    }
    public static final class Scan {
        private final AirportFacility facility;
        private int minX, maxX, minZ, maxZ, x, y, z, rowMaxZ, airBase, airEnd, phase, used, actualCells, maximumSteps;
        private boolean rowReady, columnReady, support;
        private Result result = Result.WAITING;
        private BlockPos failurePosition;
        public Scan(AirportFacility facility) {
            this.facility = facility;
            if (facility == null || !facility.validGeometry()) { result = Result.INVALID_GEOMETRY; return; }
            if (facility.clearanceChunks().isEmpty()) { result = Result.TOO_LARGE; return; }
            AABB area = facility.clearanceBounds();
            minX = (int) Math.floor(area.minX); maxX = (int) Math.ceil(area.maxX) - 1;
            minZ = (int) Math.floor(area.minZ); maxZ = (int) Math.ceil(area.maxZ) - 1;
            // Bounds here only cap editor work. Capacity is counted from actual corridor cells in phase zero.
            maximumSteps = 2 * (MAX_CELLS + (maxX - minX + 1) * (maxZ - minZ + 1) + maxX - minX + 1) + 1;
            resetCursor();
        }
        public int stepsUsed() { return used; }
        public @Nullable BlockPos failurePosition() { return failurePosition; }
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
                if (!loaded.test(pos)) return fail(Result.UNLOADED, pos);
                boolean supportCell = support && y == facility.start().getY() - 1;
                if (phase == 1) {
                    if (supportCell && !supported.test(pos)) return fail(Result.UNSUPPORTED, pos);
                    if (!supportCell && !clear.test(pos)) return fail(Result.OBSTRUCTED, pos);
                }
                if (supportCell && airBase != Integer.MIN_VALUE) y = airBase;
                else if (supportCell || ++y >= airEnd) nextColumn();
            }
            return result;
        }
        private Result fail(Result failure, BlockPos pos) {
            failurePosition = pos;
            return result = failure;
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
