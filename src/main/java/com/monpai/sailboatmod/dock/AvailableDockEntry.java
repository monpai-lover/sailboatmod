package com.monpai.sailboatmod.dock;

import net.minecraft.core.BlockPos;

public record AvailableDockEntry(
    BlockPos pos,
    String dockName,
    String ownerName,
    String nationName,
    int distance
) {
    public String toDisplayString() {
        return String.format("%s | %s | %s | %dm", dockName, ownerName, nationName, distance);
    }
}
