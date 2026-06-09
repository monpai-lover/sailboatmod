package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.nation.RoadTravelHelper;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

public final class RoadSurfaceRouteService {
    private static final int TOWN_ROAD_CONNECT_RADIUS = 96;
    private static final int ROAD_ROUTE_MARGIN = 48;
    private static final int ROAD_SURFACE_VERTICAL_SCAN = 2;
    private static final int SHORT_GAP_MAX_MISSING_BLOCKS = 3;
    private static final int MAX_SEARCH_NODES = 60_000;

    private RoadSurfaceRouteService() {
    }

    public static List<BlockPos> route(ServerLevel level, BlockPos start, BlockPos end) {
        if (level == null) {
            return List.of();
        }
        return route(new ServerRoadSurfaceWorld(level), start, end);
    }

    static List<BlockPos> routeForTest(RoadSurfaceWorld world, BlockPos start, BlockPos end) {
        return route(world, start, end);
    }

    private static List<BlockPos> route(RoadSurfaceWorld world, BlockPos start, BlockPos end) {
        if (world == null || start == null || end == null) {
            return List.of();
        }
        String startTownId = normalize(world.townIdAt(start));
        String endTownId = normalize(world.townIdAt(end));
        if (startTownId.isBlank() || endTownId.isBlank()) {
            return List.of();
        }
        BlockPos startRoad = nearestClaimedTownRoadSurface(world, startTownId, start);
        BlockPos endRoad = nearestClaimedTownRoadSurface(world, endTownId, end);
        if (startRoad == null || endRoad == null) {
            return List.of();
        }
        List<BlockPos> roadPath = findRoadSurfacePath(world, startRoad, endRoad);
        if (roadPath.size() < 2) {
            return List.of();
        }
        return combine(start, roadPath, end);
    }

    private static BlockPos nearestClaimedTownRoadSurface(RoadSurfaceWorld world, String townId, BlockPos origin) {
        BlockPos best = null;
        long bestDistance = (long) TOWN_ROAD_CONNECT_RADIUS * (long) TOWN_ROAD_CONNECT_RADIUS;
        for (ChunkPos claim : world.claimedChunks(townId)) {
            int minX = claim.x << 4;
            int minZ = claim.z << 4;
            for (int x = minX; x <= minX + 15; x++) {
                for (int z = minZ; z <= minZ + 15; z++) {
                    long xzDistance = dist2XZ(origin.getX(), origin.getZ(), x, z);
                    if (xzDistance > bestDistance) {
                        continue;
                    }
                    BlockPos surface = roadSurfaceAt(world, x, origin.getY(), z);
                    if (surface == null) {
                        continue;
                    }
                    long distance = dist2XZ(origin, surface);
                    if (distance <= bestDistance) {
                        bestDistance = distance;
                        best = surface;
                    }
                }
            }
        }
        return best == null ? null : best.immutable();
    }

    private static List<BlockPos> findRoadSurfacePath(RoadSurfaceWorld world, BlockPos start, BlockPos end) {
        if (start.equals(end)) {
            return List.of(start.immutable(), end.immutable());
        }
        SearchBounds bounds = SearchBounds.around(start, end, ROAD_ROUTE_MARGIN);
        PriorityQueue<SearchNode> open = new PriorityQueue<>(Comparator.comparingDouble(SearchNode::score));
        Map<BlockPos, Double> bestCost = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        open.add(new SearchNode(start, 0.0D, heuristic(start, end)));
        bestCost.put(start, 0.0D);
        int visited = 0;

        while (!open.isEmpty() && visited < MAX_SEARCH_NODES) {
            SearchNode current = open.poll();
            Double knownCost = bestCost.get(current.pos());
            if (knownCost == null || current.cost() > knownCost + 0.0001D) {
                continue;
            }
            if (current.pos().equals(end)) {
                return expandShortGapConnectors(reconstruct(previous, start, end));
            }
            visited++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    int nx = current.pos().getX() + dx;
                    int nz = current.pos().getZ() + dz;
                    if (!bounds.contains(nx, nz)) {
                        continue;
                    }
                    BlockPos neighbor = roadSurfaceAt(world, nx, current.pos().getY(), nz);
                    if (neighbor == null) {
                        neighbor = bridgedRoadSurfaceAt(world, current.pos(), dx, dz);
                    }
                    if (neighbor == null) {
                        continue;
                    }
                    double candidateCost = current.cost() + stepCost(current.pos(), neighbor) + shortGapPenalty(current.pos(), neighbor);
                    if (candidateCost >= bestCost.getOrDefault(neighbor, Double.MAX_VALUE)) {
                        continue;
                    }
                    bestCost.put(neighbor, candidateCost);
                    previous.put(neighbor, current.pos());
                    open.add(new SearchNode(neighbor, candidateCost, candidateCost + heuristic(neighbor, end)));
                }
            }
        }
        return List.of();
    }

    private static BlockPos roadSurfaceAt(RoadSurfaceWorld world, int x, int yHint, int z) {
        BlockPos surface = roadSurfaceNearY(world, x, yHint, z);
        if (surface != null) {
            return surface;
        }
        int surfaceY = world.surfaceY(x, z, yHint);
        return surfaceY == yHint ? null : roadSurfaceNearY(world, x, surfaceY, z);
    }

    private static BlockPos bridgedRoadSurfaceAt(RoadSurfaceWorld world, BlockPos current, int dx, int dz) {
        if (world == null || current == null || (dx == 0 && dz == 0)) {
            return null;
        }
        for (int step = 2; step <= SHORT_GAP_MAX_MISSING_BLOCKS + 1; step++) {
            BlockPos candidate = roadSurfaceAt(
                    world,
                    current.getX() + dx * step,
                    current.getY(),
                    current.getZ() + dz * step
            );
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static BlockPos roadSurfaceNearY(RoadSurfaceWorld world, int x, int yHint, int z) {
        for (int offset = 0; offset <= ROAD_SURFACE_VERTICAL_SCAN; offset++) {
            BlockPos up = roadSurfaceAtExactY(world, x, yHint + offset, z);
            if (up != null) {
                return up;
            }
            if (offset == 0) {
                continue;
            }
            BlockPos down = roadSurfaceAtExactY(world, x, yHint - offset, z);
            if (down != null) {
                return down;
            }
        }
        return null;
    }

    private static BlockPos roadSurfaceAtExactY(RoadSurfaceWorld world, int x, int y, int z) {
        if (y < world.minBuildHeight() || y >= world.maxBuildHeight()) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.hasChunkAt(pos) || !world.isWalkableRoadSurface(pos)) {
            return null;
        }
        return pos.immutable();
    }

    private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> previous, BlockPos start, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = end;
        path.add(cursor.immutable());
        while (!cursor.equals(start)) {
            cursor = previous.get(cursor);
            if (cursor == null) {
                return List.of();
            }
            path.add(cursor.immutable());
        }
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    private static List<BlockPos> expandShortGapConnectors(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return path == null ? List.of() : path;
        }
        List<BlockPos> expanded = new ArrayList<>();
        addDeduped(expanded, path.get(0));
        for (int i = 1; i < path.size(); i++) {
            BlockPos from = path.get(i - 1);
            BlockPos to = path.get(i);
            if (isShortGapTransition(from, to)) {
                int dx = Integer.compare(to.getX(), from.getX());
                int dz = Integer.compare(to.getZ(), from.getZ());
                int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
                for (int step = 1; step < steps; step++) {
                    int y = from.getY() + (int) Math.round((to.getY() - from.getY()) * (step / (double) steps));
                    addDeduped(expanded, new BlockPos(from.getX() + dx * step, y, from.getZ() + dz * step));
                }
            }
            addDeduped(expanded, to);
        }
        return expanded.size() >= 2 ? List.copyOf(expanded) : List.of();
    }

    private static boolean isShortGapTransition(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return false;
        }
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        int steps = Math.max(dx, dz);
        return steps > 1
                && steps <= SHORT_GAP_MAX_MISSING_BLOCKS + 1
                && (dx == 0 || dz == 0 || dx == dz);
    }

    private static List<BlockPos> combine(BlockPos start, List<BlockPos> roadPath, BlockPos end) {
        List<BlockPos> out = new ArrayList<>();
        addDeduped(out, start);
        for (BlockPos pos : roadPath) {
            addDeduped(out, pos);
        }
        addDeduped(out, end);
        return out.size() >= 2 ? List.copyOf(out) : List.of();
    }

    private static void addDeduped(List<BlockPos> out, BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos copy = pos.immutable();
        if (out.isEmpty() || !out.get(out.size() - 1).equals(copy)) {
            out.add(copy);
        }
    }

    private static double stepCost(BlockPos from, BlockPos to) {
        return Math.sqrt(from.distSqr(to));
    }

    private static double shortGapPenalty(BlockPos from, BlockPos to) {
        if (!isShortGapTransition(from, to)) {
            return 0.0D;
        }
        int steps = Math.max(Math.abs(to.getX() - from.getX()), Math.abs(to.getZ() - from.getZ()));
        return steps * 4.0D;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        long dx = (long) from.getX() - to.getX();
        long dz = (long) from.getZ() - to.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static long dist2XZ(BlockPos left, BlockPos right) {
        return dist2XZ(left.getX(), left.getZ(), right.getX(), right.getZ());
    }

    private static long dist2XZ(int leftX, int leftZ, int rightX, int rightZ) {
        long dx = (long) leftX - rightX;
        long dz = (long) leftZ - rightZ;
        return dx * dx + dz * dz;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    interface RoadSurfaceWorld {
        String townIdAt(BlockPos pos);

        List<ChunkPos> claimedChunks(String townId);

        boolean hasChunkAt(BlockPos pos);

        boolean isWalkableRoadSurface(BlockPos pos);

        int surfaceY(int x, int z, int fallbackY);

        int minBuildHeight();

        int maxBuildHeight();
    }

    private record SearchNode(BlockPos pos, double cost, double score) {
    }

    private record SearchBounds(int minX, int maxX, int minZ, int maxZ) {
        static SearchBounds around(BlockPos start, BlockPos end, int margin) {
            int minX = Math.min(start.getX(), end.getX()) - margin;
            int maxX = Math.max(start.getX(), end.getX()) + margin;
            int minZ = Math.min(start.getZ(), end.getZ()) - margin;
            int maxZ = Math.max(start.getZ(), end.getZ()) + margin;
            return new SearchBounds(minX, maxX, minZ, maxZ);
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static final class ServerRoadSurfaceWorld implements RoadSurfaceWorld {
        private final ServerLevel level;
        private final NationSavedData data;
        private final String dimensionId;
        private final Map<String, List<ChunkPos>> claimCache = new LinkedHashMap<>();

        private ServerRoadSurfaceWorld(ServerLevel level) {
            this.level = level;
            this.data = NationSavedData.get(level);
            this.dimensionId = level.dimension().location().toString();
        }

        @Override
        public String townIdAt(BlockPos pos) {
            TownRecord town = TownService.getTownAt(level, pos);
            return town == null ? "" : town.townId();
        }

        @Override
        public List<ChunkPos> claimedChunks(String townId) {
            String normalizedTownId = normalize(townId);
            if (normalizedTownId.isBlank()) {
                return List.of();
            }
            return claimCache.computeIfAbsent(normalizedTownId, ignored -> {
                List<ChunkPos> chunks = new ArrayList<>();
                for (NationClaimRecord claim : data.getClaimsForTown(normalizedTownId)) {
                    if (claim != null && dimensionId.equals(claim.dimensionId())) {
                        chunks.add(new ChunkPos(claim.chunkX(), claim.chunkZ()));
                    }
                }
                return List.copyOf(chunks);
            });
        }

        @Override
        public boolean hasChunkAt(BlockPos pos) {
            return level.hasChunkAt(pos);
        }

        @Override
        public boolean isWalkableRoadSurface(BlockPos pos) {
            return RoadTravelHelper.isWalkableRoadSurface(level.getBlockState(pos));
        }

        @Override
        public int surfaceY(int x, int z, int fallbackY) {
            return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, fallbackY, z)).getY() - 1;
        }

        @Override
        public int minBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public int maxBuildHeight() {
            return level.getMaxBuildHeight();
        }
    }
}
