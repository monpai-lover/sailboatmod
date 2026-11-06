package com.monpai.sailboatmod.transport.control;

import java.util.UUID;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** A conservative ground envelope whose bounded scan survives sub-block suspension vibration. */
public final class GroundTerrainWindow {
    private static final int HORIZONTAL_GRID = 4;
    private static final long MAX_CELLS = 1_048_576, CLEAR_REFRESH_TICKS = 5;
    private SweptTerrainProbe probe = new SweptTerrainProbe();
    private UUID mission, body;
    private String dimension;
    private AABB envelope;
    private long lastTick = Long.MIN_VALUE, clearedAt = Long.MIN_VALUE;

    public SweptTerrainProbe.Result scan(UUID mission, UUID body, String dimension, long tick,
            AABB requested, int budget, Predicate<BlockPos> loaded,
            BiPredicate<BlockPos, AABB> collision) {
        if (mission == null || body == null || dimension == null || dimension.isBlank()
                || tick < 0 || budget <= 0 || loaded == null || collision == null || !valid(requested)) {
            clear();
            return SweptTerrainProbe.Result.WAITING;
        }
        if (!Objects.equals(this.mission, mission) || !Objects.equals(this.body, body)
                || !Objects.equals(this.dimension, dimension) || tick < lastTick) {
            clear();
            this.mission = mission; this.body = body; this.dimension = dimension;
        }
        lastTick = tick;
        if (envelope == null || !contains(envelope, requested)) {
            AABB next = new AABB(Math.floor(requested.minX / HORIZONTAL_GRID) * HORIZONTAL_GRID,
                    Math.floor(requested.minY), Math.floor(requested.minZ / HORIZONTAL_GRID) * HORIZONTAL_GRID,
                    Math.ceil(requested.maxX / HORIZONTAL_GRID) * HORIZONTAL_GRID,
                    Math.ceil(requested.maxY), Math.ceil(requested.maxZ / HORIZONTAL_GRID) * HORIZONTAL_GRID);
            if (!valid(next) || (next.maxX-next.minX)*(next.maxY-next.minY)*(next.maxZ-next.minZ)>MAX_CELLS) {
                clear();
                return SweptTerrainProbe.Result.WAITING;
            }
            envelope = next;
            probe = new SweptTerrainProbe();
            clearedAt = Long.MIN_VALUE;
        }
        final AABB scanBounds = envelope;
        boolean[] unknown = {false}, blocked = {false};
        SweptTerrainProbe.Result result = probe.scan(scanBounds, budget, p -> {
            try {
                boolean available = loaded.test(p);
                if (!available) unknown[0] = true;
                return available;
            } catch (RuntimeException failure) { unknown[0] = true; throw failure; }
        }, p -> {
            try {
                boolean hit = collision.test(p, scanBounds);
                if (hit) blocked[0] = true;
                return hit;
            } catch (RuntimeException failure) { unknown[0] = true; throw failure; }
        });
        if (unknown[0] || blocked[0]) {
            // WAITING alone does not distinguish exhausted work from a failed world observation.
            clearedAt = Long.MIN_VALUE;
            probe = new SweptTerrainProbe();
            return blocked[0] ? SweptTerrainProbe.Result.BLOCKED : SweptTerrainProbe.Result.WAITING;
        }
        if (result == SweptTerrainProbe.Result.CLEAR) {
            clearedAt = tick;
            return result;
        }
        // Refresh a previously completed enclosing snapshot within one ordinary controller interval.
        // Expiry does not reset the current partial scan: larger player-built hulls still make progress.
        return clearedAt >= 0 && tick >= clearedAt && tick-clearedAt <= CLEAR_REFRESH_TICKS
                ? SweptTerrainProbe.Result.CLEAR : SweptTerrainProbe.Result.WAITING;
    }

    public void clear() {
        probe = new SweptTerrainProbe(); envelope = null;
        mission = body = null; dimension = null;
        lastTick = clearedAt = Long.MIN_VALUE;
    }

    private static boolean contains(AABB outer, AABB inner) {
        return outer.minX<=inner.minX && outer.minY<=inner.minY && outer.minZ<=inner.minZ
                && outer.maxX>=inner.maxX && outer.maxY>=inner.maxY && outer.maxZ>=inner.maxZ;
    }

    private static boolean valid(AABB b) {
        if (b == null || !(b.maxX>b.minX && b.maxY>b.minY && b.maxZ>b.minZ)) return false;
        for (double n : new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ})
            if (!Double.isFinite(n) || n<=Integer.MIN_VALUE+HORIZONTAL_GRID || n>=Integer.MAX_VALUE-HORIZONTAL_GRID)
                return false;
        return true;
    }
}
