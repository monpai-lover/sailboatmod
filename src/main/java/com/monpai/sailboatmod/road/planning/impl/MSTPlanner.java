package com.monpai.sailboatmod.road.planning.impl;

import com.monpai.sailboatmod.road.model.ConnectionStatus;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import net.minecraft.core.BlockPos;

import java.util.*;

public class MSTPlanner implements NetworkPlanner {
    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points.size() < 2) return List.of();

        record Edge(int from, int to, double dist) {}
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                double dist = Math.sqrt(points.get(i).distSqr(points.get(j)));
                if (dist <= maxEdgeLenBlocks) {
                    edges.add(new Edge(i, j, dist));
                }
            }
        }
        edges.sort(Comparator.comparingDouble(Edge::dist));

        int[] parent = new int[points.size()];
        int[] rank = new int[points.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;

        List<StructureConnection> result = new ArrayList<>();
        for (Edge edge : edges) {
            int rootA = find(parent, edge.from);
            int rootB = find(parent, edge.to);
            if (rootA != rootB) {
                union(parent, rank, rootA, rootB);
                result.add(new StructureConnection(points.get(edge.from), points.get(edge.to), ConnectionStatus.PLANNED));
            }
        }
        return result;
    }

    private int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private void union(int[] parent, int[] rank, int a, int b) {
        if (rank[a] < rank[b]) { parent[a] = b; }
        else if (rank[a] > rank[b]) { parent[b] = a; }
        else { parent[b] = a; rank[a]++; }
    }
}
