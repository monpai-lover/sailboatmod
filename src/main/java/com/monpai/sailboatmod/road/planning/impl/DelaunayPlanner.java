package com.monpai.sailboatmod.road.planning.impl;

import com.monpai.sailboatmod.road.model.ConnectionStatus;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import net.minecraft.core.BlockPos;

import java.util.*;

public class DelaunayPlanner implements NetworkPlanner {
    @Override
    public List<StructureConnection> plan(List<BlockPos> points, int maxEdgeLenBlocks) {
        if (points.size() < 2) return List.of();
        if (points.size() == 2) {
            double dist = Math.sqrt(points.get(0).distSqr(points.get(1)));
            if (dist <= maxEdgeLenBlocks) {
                return List.of(new StructureConnection(points.get(0), points.get(1), ConnectionStatus.PLANNED));
            }
            return List.of();
        }

        double[] xs = new double[points.size()];
        double[] zs = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            xs[i] = points.get(i).getX();
            zs[i] = points.get(i).getZ();
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]);
        }
        double dx = maxX - minX + 1, dz = maxZ - minZ + 1;
        double midX = (minX + maxX) / 2, midZ = (minZ + maxZ) / 2;
        double superSize = Math.max(dx, dz) * 10;

        int n = points.size();
        double[] allX = Arrays.copyOf(xs, n + 3);
        double[] allZ = Arrays.copyOf(zs, n + 3);
        allX[n] = midX - superSize; allZ[n] = midZ - superSize;
        allX[n+1] = midX + superSize; allZ[n+1] = midZ - superSize;
        allX[n+2] = midX; allZ[n+2] = midZ + superSize;

        record Tri(int a, int b, int c) {}
        List<Tri> triangles = new ArrayList<>();
        triangles.add(new Tri(n, n+1, n+2));
        for (int i = 0; i < n; i++) {
            List<Tri> bad = new ArrayList<>();
            for (Tri tri : triangles) {
                if (inCircumcircle(allX, allZ, tri.a, tri.b, tri.c, i)) {
                    bad.add(tri);
                }
            }
            Set<Long> boundary = new LinkedHashSet<>();
            for (Tri tri : bad) {
                int[][] edges = {{tri.a, tri.b}, {tri.b, tri.c}, {tri.c, tri.a}};
                for (int[] edge : edges) {
                    long ek = edgeKey(edge[0], edge[1]);
                    long ek2 = edgeKey(edge[1], edge[0]);
                    boolean shared = false;
                    for (Tri other : bad) {
                        if (other == tri) continue;
                        int[][] oe = {{other.a, other.b}, {other.b, other.c}, {other.c, other.a}};
                        for (int[] o : oe) {
                            if (edgeKey(o[0], o[1]) == ek || edgeKey(o[0], o[1]) == ek2) {
                                shared = true; break;
                            }
                        }
                        if (shared) break;
                    }
                    if (!shared) boundary.add(((long)edge[0] << 32) | (edge[1] & 0xFFFFFFFFL));
                }
            }
            triangles.removeAll(bad);
            for (long bk : boundary) {
                int ea = (int)(bk >> 32);
                int eb = (int)(bk & 0xFFFFFFFFL);
                triangles.add(new Tri(ea, eb, i));
            }
        }

        Set<Long> edgeSet = new HashSet<>();
        List<StructureConnection> result = new ArrayList<>();
        for (Tri tri : triangles) {
            if (tri.a >= n || tri.b >= n || tri.c >= n) continue;
            int[][] edges = {{tri.a, tri.b}, {tri.b, tri.c}, {tri.c, tri.a}};
            for (int[] edge : edges) {
                int a = Math.min(edge[0], edge[1]);
                int b = Math.max(edge[0], edge[1]);
                long ek = ((long)a << 32) | b;
                if (edgeSet.add(ek)) {
                    double dist = Math.sqrt(points.get(a).distSqr(points.get(b)));
                    if (dist <= maxEdgeLenBlocks) {
                        result.add(new StructureConnection(points.get(a), points.get(b), ConnectionStatus.PLANNED));
                    }
                }
            }
        }
        return result;
    }

    private boolean inCircumcircle(double[] xs, double[] zs, int a, int b, int c, int p) {
        double ax = xs[a] - xs[p], az = zs[a] - zs[p];
        double bx = xs[b] - xs[p], bz = zs[b] - zs[p];
        double cx = xs[c] - xs[p], cz = zs[c] - zs[p];
        double det = ax * (bz * (cx*cx + cz*cz) - cz * (bx*bx + bz*bz))
                   - bx * (az * (cx*cx + cz*cz) - cz * (ax*ax + az*az))
                   + cx * (az * (bx*bx + bz*bz) - bz * (ax*ax + az*az));
        return det > 0;
    }

    private long edgeKey(int a, int b) {
        return ((long)a << 32) | (b & 0xFFFFFFFFL);
    }
}
