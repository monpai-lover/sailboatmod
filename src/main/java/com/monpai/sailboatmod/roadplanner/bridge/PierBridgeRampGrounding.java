package com.monpai.sailboatmod.roadplanner.bridge;

import com.monpai.sailboatmod.roadplanner.compile.RoadIssue;
import net.minecraft.core.BlockPos;

import java.util.Optional;
import java.util.function.Predicate;

public class PierBridgeRampGrounding {
    private final Predicate<BlockPos> validSurface;
    private final int maxSearchBlocks;

    public PierBridgeRampGrounding(Predicate<BlockPos> validSurface, int maxSearchBlocks) {
        this.validSurface = validSurface;
        this.maxSearchBlocks = Math.max(0, maxSearchBlocks);
    }

    public Result ground(BlockPos endpoint, BlockPos directionTowardLand) {
        BlockPos cursor = endpoint.immutable();
        int stepX = Integer.compare(directionTowardLand.getX(), 0);
        int stepZ = Integer.compare(directionTowardLand.getZ(), 0);
        for (int distance = 0; distance <= maxSearchBlocks; distance++) {
            if (validSurface.test(cursor)) {
                return new Result(Optional.of(cursor), Optional.empty());
            }
            cursor = cursor.offset(stepX, 0, stepZ);
        }
        return new Result(Optional.empty(), Optional.of(new RoadIssue(
                "pier_bridge_ramp_not_grounded",
                "桥墩大桥坡段端点无法落到有效地表",
                endpoint,
                true
        )));
    }

    public record Result(Optional<BlockPos> position, Optional<RoadIssue> issue) {
    }
}
