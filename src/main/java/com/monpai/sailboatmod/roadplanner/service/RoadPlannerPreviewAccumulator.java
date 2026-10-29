package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.roadplanner.structure.RoadSpanClassifier;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded client view: a complete coarse path and nearby ghosts, never the complete construction payload. */
public final class RoadPlannerPreviewAccumulator {
    private final RoadPlannerStagedPlan plan;
    private final String source, target;
    private final BlockPos observer;
    private final TreeMap<Integer, BlockPos> path = new TreeMap<>();
    private final LinkedHashMap<BlockPos, SyncRoadPlannerPreviewPacket.GhostBlock> ghosts = new LinkedHashMap<>();
    private final boolean[] bridges = new boolean[64];

    public RoadPlannerPreviewAccumulator(RoadPlannerStagedPlan plan, String source, String target, BlockPos observer) {
        this.plan = plan; this.source = source; this.target = target; this.observer = observer.immutable();
        path.put(0, plan.nodes().get(0));
        path.put(63, plan.nodes().get(plan.nodes().size() - 1));
    }

    public void add(RoadPlannerStagedPlan.Stage stage, SyncRoadPlannerPreviewPacket update) {
        List<BlockPos> nodes = update.pathNodes();
        for (int index = 0; index < nodes.size(); index++) {
            double at = stage.index() + (nodes.size() <= 1 ? 0 : (double) index / (nodes.size() - 1));
            int slot = Math.max(0, Math.min(63, (int) Math.round(63 * at / plan.totalStages())));
            if (slot == 0 && !(stage.index() == 0 && index == 0)) continue;
            if (slot == 63 && !(stage.index() == plan.totalStages() - 1 && index == nodes.size() - 1)) continue;
            path.put(slot, nodes.get(index));
        }
        for (var block : update.ghostBlocks()) ghosts.put(block.pos(), block);
        if (ghosts.size() > 2048) {
            List<SyncRoadPlannerPreviewPacket.GhostBlock> nearest = ghosts.values().stream()
                    .sorted(Comparator.<SyncRoadPlannerPreviewPacket.GhostBlock>comparingDouble(block -> block.pos().distSqr(observer))
                            .thenComparingLong(block -> block.pos().asLong())).limit(2048).toList();
            ghosts.clear();
            nearest.forEach(block -> ghosts.put(block.pos(), block));
        }
        if (RoadSpanClassifier.isBridge(stage.type()) || !update.bridgeRanges().isEmpty()) {
            int from = Math.max(0, (int) Math.floor(63.0 * stage.index() / plan.totalStages()));
            int to = Math.min(63, (int) Math.ceil(63.0 * (stage.index() + 1) / plan.totalStages()));
            for (int slot = from; slot <= to; slot++) bridges[slot] = true;
        }
    }

    public SyncRoadPlannerPreviewPacket snapshot(boolean ready) {
        List<Map.Entry<Integer, BlockPos>> entries = new ArrayList<>(path.entrySet());
        List<SyncRoadPlannerPreviewPacket.BridgeRange> ranges = new ArrayList<>();
        int run = -1;
        for (int segment = 0; segment < entries.size() - 1; segment++) {
            boolean bridge = false;
            for (int slot = entries.get(segment).getKey(); slot <= entries.get(segment + 1).getKey(); slot++) bridge |= bridges[slot];
            if (bridge && run < 0) run = segment;
            if (!bridge && run >= 0) { ranges.add(new SyncRoadPlannerPreviewPacket.BridgeRange(run, segment - 1)); run = -1; }
        }
        if (run >= 0) ranges.add(new SyncRoadPlannerPreviewPacket.BridgeRange(run, entries.size() - 2));
        List<BlockPos> nodes = entries.stream().map(Map.Entry::getValue).toList();
        return new SyncRoadPlannerPreviewPacket(source, target, List.copyOf(ghosts.values()), nodes,
                (int) Math.min(Integer.MAX_VALUE, Math.ceil(plan.distance()) + 1), nodes.get(0), nodes.get(nodes.size() - 1),
                observer, ready, List.of(), "", ranges);
    }
}
