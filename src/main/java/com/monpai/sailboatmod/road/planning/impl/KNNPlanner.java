package com.monpai.sailboatmod.road.planning.impl;

import com.monpai.sailboatmod.road.model.ConnectionStatus;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import net.minecraft.core.BlockPos;

import java.util.*;

public class KNNPlanner implements NetworkPlanner {
    private static final int K = 3;

    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points.size() < 2) return List.of();

        Set<Long> edgeSet = new HashSet<>();
        List<StructureConnection> result = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            record Neighbor(int index, double dist) {}
            List<Neighbor> neighbors = new ArrayList<>();
            for (int j = 0; j < points.size(); j++) {
                if (i == j) continue;
                double dist = Math.sqrt(points.get(i).distSqr(points.get(j)));
                if (dist <= maxEdgeLenBlocks) {
                    neighbors.add(new Neighbor(j, dist));
                }
            }
            neighbors.sort(Comparator.comparingDouble(Neighbor::dist));

            int count = Math.min(K, neighbors.size());
            for (int k = 0; k < count; k++) {
                int a = Math.min(i, neighbors.get(k).index);
                int b = Math.max(i, neighbors.get(k).index);
                long ek = ((long) a << 32) | b;
                if (edgeSet.add(ek)) {
                    result.add(new StructureConnection(points.get(a), points.get(b), ConnectionStatus.PLANNED));
                }
            }
        }
        return result;
    }
}
