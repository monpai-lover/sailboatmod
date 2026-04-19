package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;
import java.util.List;

public record RoadSegment(BlockPos center, List<BlockPos> blockPositions, int height) {}
