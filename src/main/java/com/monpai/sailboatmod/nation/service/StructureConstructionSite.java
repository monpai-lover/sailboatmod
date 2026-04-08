package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StructureConstructionSite {
    private static final float BASE_MS_PER_BUILD = 500.0F;
    private static final float SELF_BUILD_MS_PER_BUILD = 1500.0F;

    private final ServerLevel level;
    private final BlockPos origin;
    private final String blueprintId;
    private final List<BlueprintService.BlueprintBlock> blocks;
    private final Map<BlockPos, BlueprintService.BlueprintBlock> blockMap = new HashMap<>();
    private final Set<BlockPos> placedBlockPosSet = new HashSet<>();
    private final BlueprintService.PlacementBounds bounds;
    private final int rotation;
    private final List<BlockPos> scaffoldPositions;
    private final boolean selfBuilding;

    private float msToNextBuild = BASE_MS_PER_BUILD;
    private int lastProgressStep = 0;

    private StructureConstructionSite(ServerLevel level,
                                      BlockPos origin,
                                      String blueprintId,
                                      List<BlueprintService.BlueprintBlock> blocks,
                                      BlueprintService.PlacementBounds bounds,
                                      int rotation,
                                      List<BlockPos> scaffoldPositions,
                                      boolean selfBuilding) {
        this.level = level;
        this.origin = origin;
        this.blueprintId = blueprintId;
        this.blocks = orderBlocks(blocks, origin);
        this.bounds = bounds;
        this.rotation = rotation;
        this.scaffoldPositions = List.copyOf(scaffoldPositions);
        this.selfBuilding = selfBuilding;
        for (BlueprintService.BlueprintBlock block : this.blocks) {
            blockMap.put(block.relativePos(), block);
        }
        refreshPlacedBlocks();
    }

    static StructureConstructionSite create(ServerLevel level,
                                            BlockPos origin,
                                            BlueprintService.BlueprintPlacement placement,
                                            List<BlockPos> scaffoldPositions,
                                            boolean selfBuilding) {
        return new StructureConstructionSite(
                level,
                origin,
                placement.blueprintId(),
                placement.blockData(),
                placement.bounds(),
                placement.rotation(),
                scaffoldPositions,
                selfBuilding
        );
    }

    public void tick(int builderCount, boolean fastBuild) {
        refreshPlacedBlocks();
        if (isComplete()) {
            return;
        }

        int effectiveBuilders = Math.max(0, builderCount);
        if (effectiveBuilders <= 0 && !selfBuilding && !fastBuild) {
            return;
        }

        float msPerBuild = BASE_MS_PER_BUILD;
        if (effectiveBuilders > 0) {
            msPerBuild = (3.0F * BASE_MS_PER_BUILD) / (effectiveBuilders + 2.0F);
        } else if (selfBuilding || fastBuild) {
            msPerBuild = SELF_BUILD_MS_PER_BUILD;
        }
        if (fastBuild) {
            msPerBuild = Math.min(msPerBuild, 50.0F);
        }
        if (msToNextBuild > msPerBuild) {
            msToNextBuild = msPerBuild;
        }
        msToNextBuild -= fastBuild ? 500.0F : 50.0F;
        while (msToNextBuild <= 0.0F && !isComplete()) {
            msToNextBuild += msPerBuild;
            if (!buildNextBlock()) {
                break;
            }
        }
    }

    public boolean isComplete() {
        return placedBlockPosSet.size() >= getBlocksTotal();
    }

    public int getBlocksPlaced() {
        return placedBlockPosSet.size();
    }

    public int getBlocksTotal() {
        return blocks.size();
    }

    public int progressPercent() {
        return getBlocksTotal() <= 0 ? 100 : (getBlocksPlaced() * 100) / getBlocksTotal();
    }

    public int progressStep() {
        return getBlocksTotal() <= 0 ? 10 : (getBlocksPlaced() * 10) / getBlocksTotal();
    }

    public boolean consumeProgressUpdate() {
        int step = progressStep();
        if (step > lastProgressStep && !isComplete()) {
            lastProgressStep = step;
            return true;
        }
        return false;
    }

    public BlockPos origin() {
        return origin;
    }

    public String blueprintId() {
        return blueprintId;
    }

    public BlueprintService.PlacementBounds bounds() {
        return bounds;
    }

    public int rotation() {
        return rotation;
    }

    public List<BlockPos> scaffoldPositions() {
        return scaffoldPositions;
    }

    public BlockPos anchorPos() {
        return bounds.centerAtY(bounds.min().getY() + 1);
    }

    public BlockPos focusPos() {
        refreshPlacedBlocks();
        BlueprintService.BlueprintBlock nextBlock = findNextBlockToPlace();
        return nextBlock != null ? nextBlock.relativePos() : anchorPos();
    }

    public BlueprintService.BlueprintBlock currentTargetBlock() {
        refreshPlacedBlocks();
        return findNextBlockToPlace();
    }

    public List<BlueprintService.BlueprintBlock> remainingBlocks() {
        refreshPlacedBlocks();
        List<BlueprintService.BlueprintBlock> remaining = new ArrayList<>();
        for (BlueprintService.BlueprintBlock block : blocks) {
            if (!placedBlockPosSet.contains(block.relativePos())) {
                remaining.add(block);
            }
        }
        return List.copyOf(remaining);
    }

    public boolean advanceOneStep() {
        refreshPlacedBlocks();
        return buildNextBlock();
    }

    public BlockPos approachPos(BlockPos workerPos) {
        BlockPos focus = focusPos();
        BlockPos fallback = anchorPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        int minX = bounds.min().getX() - 1;
        int maxX = bounds.max().getX() + 1;
        int minZ = bounds.min().getZ() - 1;
        int maxZ = bounds.max().getZ() + 1;
        int preferredY = bounds.min().getY();

        for (int x = minX; x <= maxX; x++) {
            best = considerApproachCandidate(workerPos, focus, preferredY, new BlockPos(x, preferredY, minZ), best, bestScore);
            if (best != null) {
                bestScore = scoreApproach(best, workerPos, focus);
            }
            best = considerApproachCandidate(workerPos, focus, preferredY, new BlockPos(x, preferredY, maxZ), best, bestScore);
            if (best != null) {
                bestScore = scoreApproach(best, workerPos, focus);
            }
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            best = considerApproachCandidate(workerPos, focus, preferredY, new BlockPos(minX, preferredY, z), best, bestScore);
            if (best != null) {
                bestScore = scoreApproach(best, workerPos, focus);
            }
            best = considerApproachCandidate(workerPos, focus, preferredY, new BlockPos(maxX, preferredY, z), best, bestScore);
            if (best != null) {
                bestScore = scoreApproach(best, workerPos, focus);
            }
        }

        return best != null ? best : fallback;
    }

    private void refreshPlacedBlocks() {
        placedBlockPosSet.clear();
        for (BlueprintService.BlueprintBlock block : blocks) {
            if (matchesExpectedState(block)) {
                placedBlockPosSet.add(block.relativePos());
            }
        }
    }

    private boolean buildNextBlock() {
        BlueprintService.BlueprintBlock nextBlock = findNextBlockToPlace();
        if (nextBlock == null) {
            return false;
        }
        placeBlock(nextBlock);
        return true;
    }

    private BlueprintService.BlueprintBlock findNextBlockToPlace() {
        for (BlueprintService.BlueprintBlock block : blocks) {
            if (placedBlockPosSet.contains(block.relativePos())) {
                continue;
            }
            BlockPos pos = block.relativePos();
            BlockState currentState = level.getBlockState(pos);
            if (!currentState.equals(block.state()) && !canReplace(currentState)) {
                continue;
            }
            if (hasPlacementSupport(block)) {
                return block;
            }
        }
        return null;
    }

    private boolean hasPlacementSupport(BlueprintService.BlueprintBlock block) {
        int minY = bounds.min().getY();
        BlockPos pos = block.relativePos();
        if (pos.getY() <= minY) {
            return true;
        }
        if (isStableWorldBlock(pos.below())) {
            return true;
        }
        for (BlockPos neighbor : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
            if (isStableWorldBlock(neighbor)) {
                return true;
            }
        }
        return false;
    }

    private boolean isStableWorldBlock(BlockPos pos) {
        if (placedBlockPosSet.contains(pos)) {
            return true;
        }
        BlueprintService.BlueprintBlock expected = blockMap.get(pos);
        if (expected != null && matchesExpectedState(expected)) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.canBeReplaced() && !state.liquid();
    }

    private boolean matchesExpectedState(BlueprintService.BlueprintBlock block) {
        return level.getBlockState(block.relativePos()).equals(block.state());
    }

    private boolean canReplace(BlockState state) {
        return state.isAir() || state.canBeReplaced() || state.liquid();
    }

    private void placeBlock(BlueprintService.BlueprintBlock block) {
        BlockPos pos = block.relativePos();
        level.setBlock(pos, block.state(), Block.UPDATE_ALL);
        applyBlockEntityData(pos, block.state(), block.nbt());
        placedBlockPosSet.add(pos);
        level.sendParticles(ParticleTypes.CLOUD,
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                3, 0.15D, 0.15D, 0.15D, 0.01D);
        level.playSound(null, pos, block.state().getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.45F, 1.0F);
    }

    private void applyBlockEntityData(BlockPos pos, BlockState state, CompoundTag tag) {
        if (tag == null) {
            return;
        }
        BlockEntity blockEntity = BlockEntity.loadStatic(pos, state, tag.copy());
        if (blockEntity != null) {
            level.setBlockEntity(blockEntity);
        }
    }

    private static List<BlueprintService.BlueprintBlock> orderBlocks(List<BlueprintService.BlueprintBlock> blocks, BlockPos origin) {
        List<BlueprintService.BlueprintBlock> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator
                .comparingInt((BlueprintService.BlueprintBlock block) -> block.relativePos().getY())
                .thenComparingInt(block -> Math.abs(block.relativePos().getX() - origin.getX())
                        + Math.abs(block.relativePos().getZ() - origin.getZ()))
                .thenComparingInt(block -> block.relativePos().getX())
                .thenComparingInt(block -> block.relativePos().getZ()));
        return List.copyOf(ordered);
    }

    private BlockPos considerApproachCandidate(BlockPos workerPos,
                                               BlockPos focusPos,
                                               int preferredY,
                                               BlockPos candidate,
                                               BlockPos best,
                                               double bestScore) {
        BlockPos standable = findStandable(candidate, preferredY);
        if (standable == null) {
            return best;
        }
        double score = scoreApproach(standable, workerPos, focusPos);
        return score < bestScore ? standable : best;
    }

    private double scoreApproach(BlockPos candidate, BlockPos workerPos, BlockPos focusPos) {
        double workerDistance = workerPos == null ? 0.0D : workerPos.distSqr(candidate);
        double focusDistance = candidate.distSqr(focusPos);
        return workerDistance + (focusDistance * 0.35D);
    }

    private BlockPos findStandable(BlockPos candidate, int preferredY) {
        for (int y = preferredY + 3; y >= preferredY - 4; y--) {
            BlockPos pos = new BlockPos(candidate.getX(), y, candidate.getZ());
            if (isStandable(pos)) {
                return pos;
            }
        }
        return null;
    }

    private boolean isStandable(BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        BlockState body = level.getBlockState(pos);
        BlockState head = level.getBlockState(pos.above());
        return !below.isAir()
                && !below.liquid()
                && below.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)
                && canOccupy(body)
                && canOccupy(head);
    }

    private boolean canOccupy(BlockState state) {
        return state.isAir() || state.canBeReplaced() || state.liquid();
    }
}
