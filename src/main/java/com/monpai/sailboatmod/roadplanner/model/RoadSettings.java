package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Objects;

public record RoadSettings(int width,
                           Block mainMaterial,
                           Block edgeMaterial,
                           boolean enableBridge,
                           boolean enableTunnel) {
    public RoadSettings {
        if (width != 3 && width != 5 && width != 7) {
            throw new IllegalArgumentException("width must be 3, 5, or 7");
        }
        mainMaterial = Objects.requireNonNull(mainMaterial, "mainMaterial");
        edgeMaterial = Objects.requireNonNull(edgeMaterial, "edgeMaterial");
    }

    public static RoadSettings defaults() {
        return new RoadSettings(5, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, true, true);
    }
}
