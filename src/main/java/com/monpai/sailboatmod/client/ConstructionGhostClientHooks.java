package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ConstructionGhostClientHooks {
    public enum TargetKind {
        BUILDING,
        ROAD
    }

    public record GhostBlock(BlockPos pos, BlockState state) {
    }

    public record BuildingPreview(String jobId,
                                  String structureId,
                                  BlockPos origin,
                                  List<GhostBlock> blocks,
                                  BlockPos targetPos,
                                  int progressPercent,
                                  int activeWorkers) {
    }

    public record RoadPreview(String jobId,
                              String roadId,
                              String sourceTownName,
                              String targetTownName,
                              List<GhostBlock> blocks,
                              BlockPos targetPos,
                              int progressPercent,
                              int activeWorkers) {
    }

    public record HammerTarget(TargetKind kind, String jobId, BlockPos hitPos) {
    }

    private static List<BuildingPreview> buildingPreviews = List.of();
    private static List<RoadPreview> roadPreviews = List.of();
    private static long lastSyncAtMs = 0L;

    public static void update(List<BuildingPreview> buildings, List<RoadPreview> roads) {
        buildingPreviews = buildings == null ? List.of() : List.copyOf(buildings);
        roadPreviews = roads == null ? List.of() : List.copyOf(roads);
        lastSyncAtMs = System.currentTimeMillis();
    }

    public static List<BuildingPreview> buildingPreviews() {
        clearIfStale();
        return new ArrayList<>(buildingPreviews);
    }

    public static List<RoadPreview> roadPreviews() {
        clearIfStale();
        return new ArrayList<>(roadPreviews);
    }

    public static HammerTarget pickTarget(Player player, double reach) {
        if (player == null || !isHoldingHammer(player)) {
            return null;
        }
        clearIfStale();

        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(Math.max(0.0D, reach)));
        Candidate best = null;

        for (BuildingPreview preview : buildingPreviews) {
            Candidate candidate = pickClosest(start, end, preview.jobId(), TargetKind.BUILDING, preview.blocks());
            if (candidate != null && (best == null || candidate.distanceSqr < best.distanceSqr)) {
                best = candidate;
            }
        }
        for (RoadPreview preview : roadPreviews) {
            Candidate candidate = pickClosest(start, end, preview.jobId(), TargetKind.ROAD, preview.blocks());
            if (candidate != null && (best == null || candidate.distanceSqr < best.distanceSqr)) {
                best = candidate;
            }
        }
        return best == null ? null : new HammerTarget(best.kind, best.jobId, best.hitPos);
    }

    public static boolean isHoldingPreviewTool(Player player) {
        if (player == null) {
            return false;
        }
        return player.getMainHandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())
                || player.getOffhandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())
                || player.getMainHandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get())
                || player.getOffhandItem().is(ModItems.BANK_CONSTRUCTOR_ITEM.get())
                || player.getMainHandItem().is(ModItems.ROAD_PLANNER_ITEM.get())
                || player.getOffhandItem().is(ModItems.ROAD_PLANNER_ITEM.get());
    }

    public static boolean isHoldingHammer(Player player) {
        if (player == null) {
            return false;
        }
        return player.getMainHandItem().is(ModItems.BUILDER_HAMMER_ITEM.get())
                || player.getOffhandItem().is(ModItems.BUILDER_HAMMER_ITEM.get());
    }

    private static Candidate pickClosest(Vec3 start,
                                         Vec3 end,
                                         String jobId,
                                         TargetKind kind,
                                         List<GhostBlock> blocks) {
        if (jobId == null || jobId.isBlank() || blocks == null || blocks.isEmpty()) {
            return null;
        }
        Candidate best = null;
        for (GhostBlock block : blocks) {
            if (block == null || block.pos() == null) {
                continue;
            }
            Optional<Vec3> hit = new AABB(block.pos()).inflate(0.002D).clip(start, end);
            if (hit.isEmpty()) {
                continue;
            }
            double distanceSqr = hit.get().distanceToSqr(start);
            if (best == null || distanceSqr < best.distanceSqr) {
                best = new Candidate(kind, jobId, block.pos(), distanceSqr);
            }
        }
        return best;
    }

    private static void clearIfStale() {
        if ((System.currentTimeMillis() - lastSyncAtMs) > 2500L) {
            buildingPreviews = List.of();
            roadPreviews = List.of();
        }
    }

    private record Candidate(TargetKind kind, String jobId, BlockPos hitPos, double distanceSqr) {
        private Candidate {
            Objects.requireNonNull(kind);
        }
    }

    private ConstructionGhostClientHooks() {
    }
}
