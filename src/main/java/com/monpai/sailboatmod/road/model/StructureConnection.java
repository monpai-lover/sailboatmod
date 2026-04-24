package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;

public record StructureConnection(BlockPos from, BlockPos to, ConnectionStatus status) {
    public double distance() {
        return Math.sqrt(from.distSqr(to));
    }
    public StructureConnection withStatus(ConnectionStatus newStatus) {
        return new StructureConnection(from, to, newStatus);
    }
}
