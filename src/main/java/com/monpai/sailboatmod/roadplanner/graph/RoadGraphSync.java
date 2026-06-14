package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 把 {@link NationSavedData} 中的 {@link RoadNetworkRecord} 幂等地补建为
 * {@link RoadNetworkGraphSavedData} 里的 BUILT 边，供驿站路由（RoadGraphRoutingService）使用。
 *
 * <p>背景：道路记录(RoadNetworkRecord)与路由图(节点+边)是两套数据。只有部分写入路径会同时写图，
 * 因此存在"有道路记录但路由图里没有对应 built 边"的情况，导致驿站显示"没有可达道路"，
 * 必须先打开道路规划器才会回填。本工具统一做幂等同步以消除该差异。
 *
 * <p>幂等性依赖确定性的 edgeId / nodeId（与 RoadEditCompletionRefreshService 使用相同方案），
 * 因此重复同步只会覆盖同一条边和同两个端点节点，不会让图膨胀。
 */
public final class RoadGraphSync {
    private static final int DEFAULT_ROAD_WIDTH = 3;

    private RoadGraphSync() {
    }

    /**
     * 幂等地把单条道路写入路由图。已经是 BUILT 且 centerline 一致时跳过（返回 false）。
     *
     * @return 实际创建或更新了边时返回 true。
     */
    public static boolean syncRoad(RoadNetworkGraphSavedData graph, RoadNetworkRecord road) {
        if (graph == null || road == null || road.roadId().isBlank()) {
            return false;
        }
        List<BlockPos> centerline = road.path();
        if (centerline.size() < 2) {
            return false;
        }
        UUID edgeId = edgeIdForRoadId(road.roadId());
        Optional<RoadGraphEdgeRecord> existing = graph.getEdge(edgeId);
        if (existing.isPresent() && existing.get().built() && existing.get().centerline().equals(centerline)) {
            return false;
        }

        UUID fromId = nodeIdForRoad(road.roadId(), "from");
        UUID toId = nodeIdForRoad(road.roadId(), "to");
        long createdAt = road.createdAt();
        long updatedAt = road.updatedAt();
        RoadGraphNodeRecord fromNode = new RoadGraphNodeRecord(
                fromId,
                road.dimensionId(),
                centerline.get(0),
                RoadGraphNodeRecord.Kind.NORMAL,
                road.nationId(),
                road.townId(),
                "",
                createdAt,
                updatedAt);
        RoadGraphNodeRecord toNode = new RoadGraphNodeRecord(
                toId,
                road.dimensionId(),
                centerline.get(centerline.size() - 1),
                RoadGraphNodeRecord.Kind.NORMAL,
                road.nationId(),
                road.townId(),
                "",
                createdAt,
                updatedAt);
        // 必须先放入两个端点节点，putEdge 才不会因节点缺失而被静默丢弃。
        graph.putNode(fromNode);
        graph.putNode(toNode);
        graph.putEdge(new RoadGraphEdgeRecord(
                edgeId,
                fromId,
                toId,
                road.dimensionId(),
                road.nationId(),
                road.townId(),
                road.creatorUuid(),
                road.creatorName(),
                road.routeSourceName(),
                road.routeTargetName(),
                road.roadId(),
                DEFAULT_ROAD_WIDTH,
                CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT,
                centerline,
                road.displayPath(),
                List.of(),
                List.of(),
                List.of(),
                createdAt,
                updatedAt));
        return true;
    }

    /**
     * 修复整张图：把当前维度下所有尚未进图的道路记录补建为 BUILT 边。
     *
     * @return 实际同步的道路条数。
     */
    public static int reconcile(ServerLevel level) {
        if (level == null) {
            return 0;
        }
        RoadNetworkGraphSavedData graph = RoadNetworkGraphSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        int synced = 0;
        for (RoadNetworkRecord road : NationSavedData.get(level).getRoadNetworks()) {
            if (road == null || !dimensionId.equals(road.dimensionId()) || road.path().size() < 2) {
                continue;
            }
            if (syncRoad(graph, road)) {
                synced++;
            }
        }
        return synced;
    }

    static UUID edgeIdForRoadId(String roadId) {
        String normalized = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(("road-graph-edge:" + normalized).getBytes(StandardCharsets.UTF_8));
    }

    static UUID nodeIdForRoad(String roadId, String suffix) {
        String normalized = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
        String safeSuffix = suffix == null ? "" : suffix.trim().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(("road-graph-node:" + normalized + ":" + safeSuffix).getBytes(StandardCharsets.UTF_8));
    }
}
