package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoadGraphRepository {
    // per-level 缓存:forLevel 每次 new 会让新实例 indexDirty=true → 每次 spatialIndex() 全量 rebuild。
    // 高频调用方(马车每 tick 判 onRoad)必须复用同一实例,靠 indexDirty 只在写时置脏来避免重建。
    // 必须在世界卸载/服务器停止时 clearCache(),否则单机切存档会串路网数据。
    private static final Map<ResourceKey<Level>, RoadGraphRepository> LEVEL_CACHE = new ConcurrentHashMap<>();

    private final RoadNetworkGraphSavedData data;
    private final RoadGraphSpatialIndex spatialIndex = new RoadGraphSpatialIndex();
    private boolean indexDirty = true;

    public RoadGraphRepository(RoadNetworkGraphSavedData data) {
        this.data = data == null ? new RoadNetworkGraphSavedData() : data;
    }

    public static RoadGraphRepository forLevel(ServerLevel level) {
        return new RoadGraphRepository(RoadNetworkGraphSavedData.get(level));
    }

    /** 复用 per-level 单例,供每 tick 高频查询(如马车 onRoad)使用,避免重复全量重建索引。 */
    public static RoadGraphRepository forLevelCached(ServerLevel level) {
        return LEVEL_CACHE.computeIfAbsent(level.dimension(),
                key -> new RoadGraphRepository(RoadNetworkGraphSavedData.get(level)));
    }

    /** 世界卸载/服务器停止时调用,清理 per-level 缓存,防跨存档串数据或持有失效 SavedData。 */
    public static void clearCache() {
        LEVEL_CACHE.clear();
    }

    /** 外部绕过本仓库直写 SavedData 后,可调此方法强制下次 spatialIndex() 重建索引。 */
    public void markIndexDirty() {
        this.indexDirty = true;
    }

    public void putNode(RoadGraphNodeRecord node) {
        data.putNode(node);
        indexDirty = true;
    }

    public void putEdge(RoadGraphEdgeRecord edge) {
        data.putEdge(edge);
        indexDirty = true;
    }

    public Optional<RoadGraphNodeRecord> node(UUID nodeId) {
        return data.getNode(nodeId);
    }

    public Optional<RoadGraphEdgeRecord> edge(UUID edgeId) {
        return data.getEdge(edgeId);
    }

    public Collection<RoadGraphNodeRecord> nodes() {
        return data.nodes();
    }

    public Collection<RoadGraphEdgeRecord> edges() {
        return data.edges();
    }

    public List<RoadGraphEdgeRecord> edgesForDimension(String dimensionId) {
        return data.edgesForDimension(dimensionId).stream().toList();
    }

    public RoadGraphSpatialIndex spatialIndex() {
        if (indexDirty) {
            spatialIndex.rebuild(data.edges().stream().toList());
            indexDirty = false;
        }
        return spatialIndex;
    }

    public boolean removeEdge(UUID edgeId) {
        boolean removed = data.removeEdge(edgeId);
        if (removed) {
            indexDirty = true;
        }
        return removed;
    }
}
