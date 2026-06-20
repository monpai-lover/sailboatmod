package com.monpai.sailboatmod.route.water.debug;

import net.minecraft.core.BlockPos;

import java.util.List;

/** DTOs for the route-debug HTTP tool. */
public final class RouteDebugDtos {
    private RouteDebugDtos() {
    }

    /** One pathfinding stage's result: its path + pathfinder status/nodes/reason. */
    public record StageResult(String stage, List<BlockPos> path, String status, int expandedNodes, String reason) {
    }

    /** Per-node diagnosis of a final (verified) waypoint, comparing NBT verdict vs real block. */
    public record NodeDiag(int index, BlockPos pos, boolean nbtWater, boolean realNavigable,
                           int realSurfaceY, String blockId, String howSampled, String howRouted) {
    }

    /** Full result bundle: all stage results + per-node diagnostics + human-readable summary. */
    public record RouteDebugBundle(BlockPos start, BlockPos goal,
                                   StageResult berth, StageResult coarse, StageResult fine,
                                   StageResult smooth, StageResult verified,
                                   List<NodeDiag> nodeDiags, String summary) {
    }

    /** A dock entry for the picker: position + name + zone bounds (relative to dockPos). */
    public record DockInfo(BlockPos pos, String name,
                           int zoneMinX, int zoneMaxX, int zoneMinZ, int zoneMaxZ) {
    }

    /** World->pixel transform: each {@code scale} world blocks map to one pixel. */
    public record ViewTransform(int minX, int minZ, int spanX, int spanZ, int scale, int width, int height) {
        public int px(int worldX) {
            return (worldX - minX) / scale;
        }

        public int pz(int worldZ) {
            return (worldZ - minZ) / scale;
        }
    }
}
