package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RoadPathPostProcessor {
    private RoadPathPostProcessor() {
    }

    public static List<BlockPos> process(List<BlockPos> rawPath, boolean[] bridgeMask) {
        if (rawPath == null || rawPath.size() < 2) {
            return List.of();
        }
        List<BlockPos> simplified = simplifyPath(rawPath);
        boolean[] normalizedMask = normalizeBridgeMask(bridgeMask, simplified.size());
        List<BlockPos> straightened = straightenBridgeRuns(simplified, normalizedMask);
        List<BlockPos> relaxed = relaxPathSkippingBridge(straightened, normalizedMask);
        return ensureContinuous(relaxed);
    }

    static List<BlockPos> simplifyPathForTest(List<BlockPos> rawPath) {
        return simplifyPath(rawPath);
    }

    static List<BlockPos> straightenBridgeRunsForTest(List<BlockPos> rawPath, boolean[] bridgeMask) {
        return straightenBridgeRuns(rawPath, bridgeMask);
    }

    static List<BlockPos> relaxPathSkippingBridgeForTest(List<BlockPos> rawPath, boolean[] bridgeMask) {
        return relaxPathSkippingBridge(rawPath, bridgeMask);
    }

    private static List<BlockPos> simplifyPath(List<BlockPos> rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        if (rawPath.size() < 3) {
            return rawPath.stream()
                    .filter(Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
        }
        ArrayList<BlockPos> simplified = new ArrayList<>();
        simplified.add(immutable(rawPath.get(0)));
        for (int i = 1; i < rawPath.size() - 1; i++) {
            BlockPos previous = simplified.get(simplified.size() - 1);
            BlockPos current = immutable(rawPath.get(i));
            BlockPos next = immutable(rawPath.get(i + 1));
            int dx1 = Integer.compare(current.getX() - previous.getX(), 0);
            int dz1 = Integer.compare(current.getZ() - previous.getZ(), 0);
            int dx2 = Integer.compare(next.getX() - current.getX(), 0);
            int dz2 = Integer.compare(next.getZ() - current.getZ(), 0);
            if (dx1 != dx2 || dz1 != dz2) {
                simplified.add(current);
            }
        }
        BlockPos last = immutable(rawPath.get(rawPath.size() - 1));
        if (!simplified.get(simplified.size() - 1).equals(last)) {
            simplified.add(last);
        }
        return List.copyOf(simplified);
    }

    private static List<BlockPos> straightenBridgeRuns(List<BlockPos> rawPath, boolean[] bridgeMask) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        if (rawPath.size() < 3 || bridgeMask == null || bridgeMask.length != rawPath.size()) {
            return rawPath.stream()
                    .filter(Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
        }
        ArrayList<BlockPos> straightened = new ArrayList<>(rawPath.size());
        for (BlockPos pos : rawPath) {
            straightened.add(immutable(pos));
        }
        int index = 0;
        while (index < bridgeMask.length) {
            if (!bridgeMask[index]) {
                index++;
                continue;
            }
            int runStart = index;
            while (index < bridgeMask.length && bridgeMask[index]) {
                index++;
            }
            int runEnd = index - 1;
            int anchorStart = Math.max(0, runStart - 1);
            int anchorEnd = Math.min(rawPath.size() - 1, runEnd + 1);
            BlockPos start = immutable(rawPath.get(anchorStart));
            BlockPos end = immutable(rawPath.get(anchorEnd));
            int span = Math.max(1, anchorEnd - anchorStart);
            for (int i = runStart; i <= runEnd; i++) {
                double t = (i - anchorStart) / (double) span;
                straightened.set(i, new BlockPos(
                        (int) Math.round(start.getX() + ((end.getX() - start.getX()) * t)),
                        rawPath.get(i).getY(),
                        (int) Math.round(start.getZ() + ((end.getZ() - start.getZ()) * t))
                ));
            }
        }
        return List.copyOf(straightened);
    }

    private static List<BlockPos> relaxPathSkippingBridge(List<BlockPos> rawPath, boolean[] bridgeMask) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        if (rawPath.size() < 3) {
            return rawPath.stream()
                    .filter(Objects::nonNull)
                    .map(BlockPos::immutable)
                    .toList();
        }
        ArrayList<BlockPos> relaxed = new ArrayList<>();
        relaxed.add(immutable(rawPath.get(0)));
        for (int i = 1; i < rawPath.size() - 1; i++) {
            boolean keepRigid = bridgeMask != null
                    && i < bridgeMask.length
                    && (bridgeMask[i]
                    || bridgeMask[i - 1]
                    || (i + 1 < bridgeMask.length && bridgeMask[i + 1]));
            if (keepRigid) {
                relaxed.add(immutable(rawPath.get(i)));
                continue;
            }
            BlockPos previous = immutable(rawPath.get(i - 1));
            BlockPos current = immutable(rawPath.get(i));
            BlockPos next = immutable(rawPath.get(i + 1));
            relaxed.add(new BlockPos(
                    Math.round((previous.getX() + (current.getX() * 2.0F) + next.getX()) / 4.0F),
                    current.getY(),
                    Math.round((previous.getZ() + (current.getZ() * 2.0F) + next.getZ()) / 4.0F)
            ));
        }
        relaxed.add(immutable(rawPath.get(rawPath.size() - 1)));
        return List.copyOf(relaxed);
    }

    private static boolean[] normalizeBridgeMask(boolean[] bridgeMask, int size) {
        boolean[] normalized = new boolean[Math.max(0, size)];
        if (bridgeMask == null) {
            return normalized;
        }
        for (int i = 0; i < normalized.length && i < bridgeMask.length; i++) {
            normalized[i] = bridgeMask[i];
        }
        return normalized;
    }

    private static List<BlockPos> ensureContinuous(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        ArrayList<BlockPos> continuous = new ArrayList<>();
        BlockPos previous = null;
        for (BlockPos pos : path) {
            BlockPos current = immutable(pos);
            if (previous == null) {
                continuous.add(current);
                previous = current;
                continue;
            }
            int dx = current.getX() - previous.getX();
            int dy = current.getY() - previous.getY();
            int dz = current.getZ() - previous.getZ();
            int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
            if (steps == 0) {
                continue;
            }
            for (int step = 1; step <= steps; step++) {
                BlockPos interpolated = new BlockPos(
                        previous.getX() + Math.round(dx * (step / (float) steps)),
                        previous.getY() + Math.round(dy * (step / (float) steps)),
                        previous.getZ() + Math.round(dz * (step / (float) steps))
                );
                if (continuous.isEmpty() || !continuous.get(continuous.size() - 1).equals(interpolated)) {
                    continuous.add(interpolated);
                }
            }
            previous = current;
        }
        return List.copyOf(continuous);
    }

    private static BlockPos immutable(BlockPos pos) {
        return Objects.requireNonNull(pos, "path contains null").immutable();
    }
}
