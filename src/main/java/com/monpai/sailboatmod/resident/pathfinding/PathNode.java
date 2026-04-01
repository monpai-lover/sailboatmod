package com.monpai.sailboatmod.resident.pathfinding;

import net.minecraft.core.BlockPos;

/**
 * Pathfinding node (simplified from Maple)
 */
public class PathNode implements Comparable<PathNode> {
    public final BlockPos pos;
    public double g; // cost from start
    public double h; // heuristic to goal
    public double f; // total cost (g + h)
    public PathNode parent;

    public PathNode(BlockPos pos, double g, double h, PathNode parent) {
        this.pos = pos;
        this.g = g;
        this.h = h;
        this.f = g + h;
        this.parent = parent;
    }

    @Override
    public int compareTo(PathNode other) {
        return Double.compare(this.f, other.f);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PathNode other)) return false;
        return pos.equals(other.pos);
    }

    @Override
    public int hashCode() {
        return pos.hashCode();
    }
}
