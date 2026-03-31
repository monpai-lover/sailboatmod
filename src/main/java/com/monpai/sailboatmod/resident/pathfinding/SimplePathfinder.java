package com.monpai.sailboatmod.resident.pathfinding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class SimplePathfinder {
    
    public static List<BlockPos> findPath(Level level, BlockPos start, BlockPos goal, int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        Set<BlockPos> closed = new HashSet<>();
        Map<BlockPos, Node> nodes = new HashMap<>();
        
        Node startNode = new Node(start, 0, heuristic(start, goal), null);
        open.add(startNode);
        nodes.put(start, startNode);
        
        int explored = 0;
        
        while (!open.isEmpty() && explored < maxNodes) {
            Node current = open.poll();
            explored++;
            
            if (current.pos.equals(goal)) {
                return buildPath(current);
            }
            
            closed.add(current.pos);
            
            for (BlockPos neighbor : getWalkableNeighbors(level, current.pos)) {
                if (closed.contains(neighbor)) continue;
                
                double newG = current.g + cost(current.pos, neighbor);
                Node neighborNode = nodes.get(neighbor);
                
                if (neighborNode == null) {
                    neighborNode = new Node(neighbor, newG, heuristic(neighbor, goal), current);
                    nodes.put(neighbor, neighborNode);
                    open.add(neighborNode);
                } else if (newG < neighborNode.g) {
                    open.remove(neighborNode);
                    neighborNode.g = newG;
                    neighborNode.f = newG + neighborNode.h;
                    neighborNode.parent = current;
                    open.add(neighborNode);
                }
            }
        }
        
        return Collections.emptyList();
    }
    
    private static List<BlockPos> getWalkableNeighbors(Level level, BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        BlockPos[] directions = {
            pos.north(), pos.south(), pos.east(), pos.west()
        };
        
        for (BlockPos dir : directions) {
            if (isWalkable(level, dir)) {
                neighbors.add(dir);
            }
        }
        
        return neighbors;
    }
    
    private static boolean isWalkable(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        
        return !below.isAir() && at.isAir() && above.isAir();
    }
    
    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + 
               Math.abs(a.getY() - b.getY()) + 
               Math.abs(a.getZ() - b.getZ());
    }
    
    private static double cost(BlockPos from, BlockPos to) {
        return from.distManhattan(to);
    }
    
    private static List<BlockPos> buildPath(Node goal) {
        List<BlockPos> path = new ArrayList<>();
        Node current = goal;
        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }
    
    private static class Node implements Comparable<Node> {
        BlockPos pos;
        double g, h, f;
        Node parent;
        
        Node(BlockPos pos, double g, double h, Node parent) {
            this.pos = pos;
            this.g = g;
            this.h = h;
            this.f = g + h;
            this.parent = parent;
        }
        
        @Override
        public int compareTo(Node other) {
            return Double.compare(this.f, other.f);
        }
    }
}
