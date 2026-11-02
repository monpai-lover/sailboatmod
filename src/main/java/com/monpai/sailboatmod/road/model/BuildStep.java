package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record BuildStep(int order, BlockPos pos, BlockState state, BuildPhase phase) {}
